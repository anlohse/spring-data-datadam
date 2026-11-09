package com.anlohse.datadam.spring.callback;

/** key is null in pre-save phases when an ID has not yet been assigned. */
public record DatadamSaveContext(Class<?> entityType, String keyPrefix, String key, boolean newEntity) {}
