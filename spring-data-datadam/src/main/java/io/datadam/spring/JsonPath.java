package io.datadam.spring;

import java.lang.reflect.Type;
import java.util.Objects;
import com.fasterxml.jackson.core.type.TypeReference;

/** A server path and the Java type of each match (not the JSONPath envelope). */
public final class JsonPath<T> {
    private final String expression;
    private final Type type;
    private JsonPath(String expression, Type type) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("JSON path must not be blank");
        this.expression = expression; this.type = Objects.requireNonNull(type);
        if (type instanceof Class<?> clazz && clazz.isPrimitive()) throw new IllegalArgumentException("Use a nullable wrapper type for JSON values");
    }
    public static <T> JsonPath<T> of(String expression, Class<T> type) { return new JsonPath<>(expression, type); }
    public static <T> JsonPath<T> of(String expression, TypeReference<T> type) { return new JsonPath<>(expression, type.getType()); }
    public String expression() { return expression; }
    public Type type() { return type; }
    public boolean isJsonPath() { return expression.startsWith("$"); }
}
