package com.anlohse.datadam.spring;

import java.lang.reflect.*;
import java.util.*;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import org.springframework.data.annotation.*;
import org.springframework.util.ReflectionUtils;

/** Version arithmetic stays in Java; Lua compares the exact JSON integer text without floating-point conversion. */
final class EntityVersion {
    private final Field field;
    EntityVersion(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        ReflectionUtils.doWithFields(type, f -> { if (f.isAnnotationPresent(Version.class)) fields.add(f); });
        if (fields.size() > 1) throw new IllegalArgumentException("Entity may declare only one @Version field");
        field = fields.isEmpty() ? null : fields.get(0);
        if (field != null) {
            if (!Set.of(Long.class, long.class, Integer.class, int.class).contains(field.getType()))
                throw new IllegalArgumentException("@Version requires Long, long, Integer or int");
            if (Modifier.isStatic(field.getModifiers()) || !type.isRecord() && Modifier.isFinal(field.getModifiers())
                || field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(DatadamTimeToLive.class)
                || field.isAnnotationPresent(CreatedDate.class) || field.isAnnotationPresent(LastModifiedDate.class)
                || field.isAnnotationPresent(CreatedBy.class) || field.isAnnotationPresent(LastModifiedBy.class)
                || field.isAnnotationPresent(Transient.class))
                throw new IllegalArgumentException("@Version must be writable, persistent and distinct from @Id/TTL");
            ReflectionUtils.makeAccessible(field);
        }
    }
    boolean present() { return field != null; }
    String property() { return field.getName(); }
    Number value(Object entity) { return present() ? (Number) ReflectionUtils.getField(field, entity) : null; }
    boolean isNew(Object entity) {
        Number value = value(entity);
        if (value != null && value.longValue() < 0) throw new IllegalArgumentException("@Version cannot be negative");
        return value == null || field.getType().isPrimitive() && value.longValue() == 0;
    }
    Number next(Object entity) {
        long value;
        if (isNew(entity)) value = field.getType().isPrimitive() ? 1 : 0;
        else {
            try { value = Math.addExact(value(entity).longValue(), 1); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("@Version overflow", e); }
        }
        if (field.getType() == Integer.class || field.getType() == int.class) {
            try { return Math.toIntExact(value); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("@Version overflow", e); }
        }
        return value;
    }
    void apply(Object entity, Number value) { ReflectionUtils.setField(field, entity, value); }
}
