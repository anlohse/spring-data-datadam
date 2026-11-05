package io.datadam.spring;

import java.util.UUID;

public final class UuidDatadamIdGenerator implements DatadamIdGenerator {
    @Override public void validate(IdGenerationContext context) {
        if (context.idType() != String.class && context.idType() != UUID.class)
            throw new IllegalArgumentException("UUID generation requires a String or UUID @Id: " + context.entityType().getName());
    }
    @Override public Object generateId(IdGenerationContext context) {
        validate(context);
        UUID id = UUID.randomUUID();
        return context.idType() == String.class ? id.toString() : id;
    }
}
