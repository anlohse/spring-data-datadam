package com.anlohse.datadam.spring;

/** Metadata available to an ID generator; no database calls or entity mutation are required. */
public record IdGenerationContext(Class<?> entityType, Class<?> idType, String keyPrefix) {}
