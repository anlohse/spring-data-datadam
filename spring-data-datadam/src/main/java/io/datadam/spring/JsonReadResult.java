package io.datadam.spring;

import java.util.*;

/** Missing key: false/empty; no JSONPath matches: true/empty; JSON null: true/list containing null. */
public record JsonReadResult<T>(boolean keyExists, List<T> values) {
    public JsonReadResult {
        values = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values)));
        if (!keyExists && !values.isEmpty()) throw new IllegalArgumentException("Missing keys cannot have matches");
    }
}
