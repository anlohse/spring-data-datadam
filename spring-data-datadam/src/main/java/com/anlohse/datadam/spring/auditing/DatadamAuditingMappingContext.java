package com.anlohse.datadam.spring.auditing;

import java.time.*;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.mapping.*;
import org.springframework.data.mapping.context.AbstractMappingContext;
import org.springframework.data.mapping.model.*;
import org.springframework.data.util.TypeInformation;

/** Mapping metadata used by Spring Data's auditing handler, including immutable record accessors. */
final class DatadamAuditingMappingContext extends AbstractMappingContext<BasicPersistentEntity<?, DatadamAuditingMappingContext.AuditProperty>, DatadamAuditingMappingContext.AuditProperty> {
    DatadamAuditingMappingContext() {
        setSimpleTypeHolder(new SimpleTypeHolder(Set.of(Instant.class, LocalDateTime.class, LocalDate.class,
            LocalTime.class, OffsetDateTime.class, ZonedDateTime.class, Duration.class, UUID.class,
            java.math.BigDecimal.class, java.math.BigInteger.class), true));
    }
    @Override protected <T> BasicPersistentEntity<?, AuditProperty> createPersistentEntity(TypeInformation<T> type) {
        return new BasicPersistentEntity<>(type);
    }
    @Override protected AuditProperty createPersistentProperty(Property property, BasicPersistentEntity<?, AuditProperty> owner, SimpleTypeHolder simpleTypes) {
        return new AuditProperty(property, owner, simpleTypes);
    }
    @Override protected boolean shouldCreatePersistentEntityFor(TypeInformation<?> type) {
        return !type.getType().getName().startsWith("java.") && super.shouldCreatePersistentEntityFor(type);
    }
    static final class AuditProperty extends AnnotationBasedPersistentProperty<AuditProperty> {
        AuditProperty(Property property, PersistentEntity<?, AuditProperty> owner, SimpleTypeHolder simpleTypes) { super(property, owner, simpleTypes); }
        @Override protected Association<AuditProperty> createAssociation() { return new Association<>(this, null); }
    }
}
