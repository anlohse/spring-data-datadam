package io.datadam.spring;

/**
 * Supply a fresh ID of exactly the mapped type. Implementations shared as Spring beans must be thread-safe.
 * Allocation can happen even if persistence later fails; unused values are not reclaimed.
 */
@FunctionalInterface
public interface DatadamIdGenerator {
    Object generateId(IdGenerationContext context);
    /** Called when a repository is created and before direct template saves. */
    default void validate(IdGenerationContext context) {}
}
