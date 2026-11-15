package com.anlohse.datadam.spring;

import java.util.List;

/** Ordered differences between an entity declaration and the observed schema; never performs DDL. */
public record IndexSchemaComparison(String index, IndexInfo actual, List<Difference> differences) {
    public IndexSchemaComparison { differences = List.copyOf(differences); }
    public boolean exists() { return actual != null; }
    public boolean compatible() { return exists() && differences.isEmpty(); }
    public record Difference(String property, Object expected, Object actual) {}
    public String describe() {
        return differences.stream().map(d -> d.property() + ": expected=" + d.expected() + ", actual=" + d.actual())
            .collect(java.util.stream.Collectors.joining("; "));
    }
}
