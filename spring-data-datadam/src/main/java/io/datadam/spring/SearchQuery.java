package io.datadam.spring;

import java.util.*;
import org.springframework.data.domain.Sort;

public record SearchQuery(String expression, Map<String, ?> parameters, Sort sort) {
    public SearchQuery {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Search expression is required");
        parameters = Map.copyOf(parameters);
        Objects.requireNonNull(sort);
        if (sort.stream().count() > 1) throw new IllegalArgumentException("FT.SEARCH supports one SORTBY field");
        if (sort.stream().anyMatch(o -> o.isIgnoreCase() || o.getNullHandling() != Sort.NullHandling.NATIVE))
            throw new IllegalArgumentException("Only native sort semantics are supported");
    }
    public static SearchQuery of(String expression) { return new SearchQuery(expression, Map.of(), Sort.unsorted()); }
}
