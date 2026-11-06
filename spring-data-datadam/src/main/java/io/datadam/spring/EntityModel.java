package io.datadam.spring;

import java.lang.reflect.*;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.util.ReflectionUtils;

public final class EntityModel<T> {
    private final Class<T> type;
    private final String prefix;
    private final Field id;
    private final EntityTtl ttl;
    private final DatadamGeneratedId generatedId;
    private final EntityVersion version;
    private final DatadamStorage storage;
    public EntityModel(Class<T> type) {
        this.type = type;
        var document = type.getAnnotation(DatadamDocument.class);
        if (document == null || document.prefix().isBlank()) throw new IllegalArgumentException("Entity needs @DatadamDocument with a prefix: " + type);
        prefix = document.prefix();
        storage = document.storage();
        // SCAN MATCH treats these as glob operators. Reject ambiguous keyspaces.
        if (prefix.matches(".*[\\[\\]*?\\\\].*")) throw new IllegalArgumentException("Prefix cannot contain glob operators");
        List<Field> ids = new ArrayList<>();
        ReflectionUtils.doWithFields(type, f -> { if (f.isAnnotationPresent(Id.class)) ids.add(f); });
        if (ids.size() != 1) throw new IllegalArgumentException("Entity needs exactly one @Id field: " + type);
        id = ids.get(0);
        if (Modifier.isStatic(id.getModifiers())) throw new IllegalArgumentException("@Id must be an instance field");
        ReflectionUtils.makeAccessible(id);
        List<Field> generatedFields = new ArrayList<>();
        ReflectionUtils.doWithFields(type, f -> { if (f.isAnnotationPresent(DatadamGeneratedId.class)) generatedFields.add(f); });
        if (generatedFields.size() > 1 || (!generatedFields.isEmpty() && !generatedFields.get(0).equals(id)))
            throw new IllegalArgumentException("@DatadamGeneratedId must annotate the entity's single @Id field");
        generatedId = id.getAnnotation(DatadamGeneratedId.class);
        if (generatedId != null) {
            if (!type.isRecord() && Modifier.isFinal(id.getModifiers()))
                throw new IllegalArgumentException("Generated IDs require a mutable @Id field or a record");
            if (!Set.of(String.class, UUID.class, Byte.class, Short.class, Integer.class, Long.class, byte.class, short.class, int.class, long.class).contains(id.getType()))
                throw new IllegalArgumentException("Generated IDs support String, UUID and integral types");
            if (!generatedId.generator().isEmpty() && generatedId.generator().isBlank())
                throw new IllegalArgumentException("ID generator bean name cannot be blank");
        }
        ttl = new EntityTtl(type, document);
        version = new EntityVersion(type);
    }
    public Class<T> type() { return type; }
    public String prefix() { return prefix; }
    public DatadamStorage storage() { return storage; }
    public boolean isHash() { return storage == DatadamStorage.HASH; }
    public Object id(T entity) { return ReflectionUtils.getField(id, entity); }
    public Class<?> idType() { return id.getType(); }
    public String idProperty() { return id.getName(); }
    public boolean generatesId() { return generatedId != null; }
    public String idGeneratorName() { return generatedId == null || generatedId.generator().isEmpty() ? "datadamIdGenerator" : generatedId.generator(); }
    public IdGenerationContext idGenerationContext() { return new IdGenerationContext(type, id.getType(), prefix); }
    public boolean hasMissingId(T entity) {
        Object value = id(entity);
        return value == null || value instanceof String text && text.isBlank()
            || id.getType().isPrimitive() && value instanceof Number number && number.doubleValue() == 0;
    }
    /** State detection is local; it does not test whether an assigned ID is already stored. */
    public boolean isNew(T entity) { return entity instanceof Persistable<?> persistable ? persistable.isNew() : isVersioned() ? version.isNew(entity) : hasMissingId(entity); }
    public boolean isVersioned() { return version.present(); }
    public String versionProperty() { return version.property(); }
    public Number version(T entity) { return version.value(entity); }
    public Number nextVersion(T entity) { return version.next(entity); }
    public void validateVersionState(T entity) {
        if (isVersioned() && entity instanceof Persistable<?> persistable && persistable.isNew() != version.isNew(entity))
            throw new IllegalArgumentException("Persistable.isNew must agree with @Version state");
        if (isVersioned()) version.isNew(entity); // also validates negative values
    }
    public void applyVersion(T entity, Number value) { version.apply(entity, value); }
    public void validateGeneratedId(Object value) {
        Class<?> expected = switch (id.getType().getName()) {
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            default -> id.getType();
        };
        if (value == null || !expected.isInstance(value) || value instanceof String text && text.isBlank()
            || id.getType().isPrimitive() && value instanceof Number number && number.longValue() == 0)
            throw new IllegalArgumentException("ID generator must return a nonempty value of type " + expected.getName());
    }
    /** Prepare an immutable result before writing. Mutable input is changed only after a successful write. */
    @SuppressWarnings("unchecked")
    public <S extends T> S recordWithId(S entity, Object value) {
        return recordWithValues(entity, Map.of(id.getName(), value));
    }
    @SuppressWarnings("unchecked")
    public <S extends T> S recordWithValues(S entity, Map<String, Object> replacements) {
        if (!type.isRecord()) return entity;
        try {
            var components = type.getRecordComponents();
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var accessor = components[i].getAccessor();
                ReflectionUtils.makeAccessible(accessor);
                values[i] = replacements.containsKey(components[i].getName()) ? replacements.get(components[i].getName()) : accessor.invoke(entity);
            }
            var constructor = type.getDeclaredConstructor(types);
            ReflectionUtils.makeAccessible(constructor);
            return (S) constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot reconstruct record with managed values: " + type.getName(), e);
        }
    }
    public void applyGeneratedId(T entity, Object value) {
        if (!type.isRecord()) ReflectionUtils.setField(id, entity, value);
    }
    public OptionalLong timeToLiveMilliseconds(T entity) { return ttl.milliseconds(entity); }
    public String key(Object value) {
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("A nonempty ID must be assigned before save");
        return prefix + value;
    }
    public DatadamIndex index(String name, DatadamIndex.Kind kind) {
        return Arrays.stream(type.getAnnotationsByType(DatadamIndex.class))
            .filter(i -> i.name().equals(name) && i.kind() == kind).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Undeclared " + kind + " index: " + name));
    }
}
