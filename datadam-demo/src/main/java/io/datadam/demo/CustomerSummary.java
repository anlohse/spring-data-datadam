package io.datadam.demo;
import io.datadam.spring.annotation.ProjectionProperty;
/** Read model: it is deliberately not a persistable entity. */
public record CustomerSummary(String name,@ProjectionProperty("city") String town,Integer age) {}
