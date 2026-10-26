package io.datadam.spring;

import java.lang.reflect.Field;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
import org.springframework.util.ReflectionUtils;

public final class EntityModel<T> {
    private final Class<T> type;
    private final String prefix;
    private final Field id;
    private final EntityTtl ttl;
    public EntityModel(Class<T> type) {
        this.type = type;
        var document = type.getAnnotation(DatadamDocument.class);
        if (document == null || document.prefix().isBlank()) throw new IllegalArgumentException("Entity needs @DatadamDocument with a prefix: " + type);
        prefix = document.prefix();
        // SCAN MATCH treats these as glob operators. Reject ambiguous keyspaces.
        if (prefix.matches(".*[\\[\\]*?\\\\].*")) throw new IllegalArgumentException("Prefix cannot contain glob operators");
        List<Field> ids = new ArrayList<>();
        ReflectionUtils.doWithFields(type, f -> { if (f.isAnnotationPresent(Id.class)) ids.add(f); });
        if (ids.size() != 1) throw new IllegalArgumentException("Entity needs exactly one @Id field: " + type);
        id = ids.get(0);
        ReflectionUtils.makeAccessible(id);
        ttl = new EntityTtl(type, document);
    }
    public Class<T> type() { return type; }
    public String prefix() { return prefix; }
    public Object id(T entity) { return ReflectionUtils.getField(id, entity); }
    public Class<?> idType() { return id.getType(); }
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
