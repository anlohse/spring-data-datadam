package io.datadam.spring.auditing;

import java.util.*;
import io.datadam.spring.annotation.DatadamTimeToLive;
import org.springframework.data.annotation.*;
import org.springframework.data.auditing.*;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Auditable;

/** Optional annotation-based auditing, delegated to Spring Data's AuditingHandler. */
public final class DatadamAuditing {
    private static final List<Class<? extends java.lang.annotation.Annotation>> ANNOTATIONS = List.of(CreatedDate.class, CreatedBy.class, LastModifiedDate.class, LastModifiedBy.class);
    private final DatadamAuditingMappingContext mapping = new DatadamAuditingMappingContext();
    private final AuditingHandler handler = AuditingHandler.from(mapping);
    public DatadamAuditing(DateTimeProvider timeProvider, AuditorAware<?> auditor, boolean modifyOnCreate, boolean setDates) {
        handler.setDateTimeProvider(Objects.requireNonNull(timeProvider));
        handler.setDateTimeForNow(setDates);
        handler.setModifyOnCreation(modifyOnCreate);
        if (auditor != null) handler.setAuditorAware(auditor);
        handler.afterPropertiesSet();
    }
    public void validate(Class<?> type) {
        if (Auditable.class.isAssignableFrom(type)) throw new IllegalArgumentException("Datadam auditing currently uses annotations; Auditable implementations are unsupported");
        var metadata = mapping.getRequiredPersistentEntity(type);
        for (var property : metadata) {
            long roles = ANNOTATIONS.stream().filter(property::isAnnotationPresent).count();
            if (roles > 1 || roles > 0 && (property.isIdProperty() || property.isAnnotationPresent(DatadamTimeToLive.class)))
                throw new IllegalArgumentException("Audit properties must have one role and cannot be @Id or TTL: " + property.getName());
        }
        var paths = mapping.findPersistentPropertyPaths(type, p -> ANNOTATIONS.stream().anyMatch(p::isAnnotationPresent));
        if (paths.stream().anyMatch(path -> path.getLength() > 1))
            throw new IllegalArgumentException("Datadam auditing currently supports audit annotations at the entity root only");
    }
    public Set<String> properties(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (var property : mapping.getRequiredPersistentEntity(type))
            if (ANNOTATIONS.stream().anyMatch(property::isAnnotationPresent)) names.add(property.getName());
        return names;
    }
    public <T> T audit(T entity, boolean isNew) { return isNew ? handler.markCreated(entity) : handler.markModified(entity); }
    /** Values at the root level are protected from callback changes. Nested auditing is not part of this API. */
    public Map<String, Object> snapshot(Object entity, boolean creationOnly) {
        var metadata = mapping.getRequiredPersistentEntity(entity.getClass());
        var accessor = metadata.getPropertyAccessor(entity);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var property : metadata) {
            boolean include = creationOnly ? property.isAnnotationPresent(CreatedDate.class) || property.isAnnotationPresent(CreatedBy.class)
                : ANNOTATIONS.stream().anyMatch(property::isAnnotationPresent);
            if (include) values.put(property.getName(), copyValue(accessor.getProperty(property)));
        }
        return values;
    }
    public void assertUnchanged(Map<String, Object> expected, Object entity) {
        var metadata = mapping.getRequiredPersistentEntity(entity.getClass());
        var accessor = metadata.getPropertyAccessor(entity);
        for (var entry : expected.entrySet()) {
            var property = metadata.getRequiredPersistentProperty(entry.getKey());
            if (!Objects.equals(entry.getValue(), accessor.getProperty(property)))
                throw new IllegalArgumentException("Callback must not change protected audit property: " + entry.getKey());
        }
    }
    private Object copyValue(Object value) {
        if (value instanceof Date date) return date.clone();
        if (value instanceof Calendar calendar) return calendar.clone();
        return value;
    }
}
