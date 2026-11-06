package io.datadam.spring;

/** Distinguishes a popped JSON null (true/null) from an empty or non-array match (false/null). */
public record JsonPopResult<T>(boolean popped, T value) {
    public JsonPopResult {
        if (!popped && value != null) throw new IllegalArgumentException("An unsuccessful pop cannot have a value");
    }
}
