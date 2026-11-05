package io.datadam.spring;

import java.util.*;

/** Immutable, argument-safe IDX predicates. Ordering follows the declared compound index. */
public record CompoundQuery(List<Condition> conditions, boolean descending) {
    public CompoundQuery { conditions = List.copyOf(conditions); }
    public static CompoundQuery all() { return new CompoundQuery(List.of(), false); }
    public CompoundQuery where(String field, String operator, Object value) {
        var copy = new ArrayList<>(conditions);
        copy.add(new Condition(field, operator, value));
        return new CompoundQuery(copy, descending);
    }
    public CompoundQuery desc() { return new CompoundQuery(conditions, true); }
    public List<Object> arguments(String index) {
        List<Object> args = new ArrayList<>();
        args.add(index);
        conditions.forEach(c -> Collections.addAll(args, c.field(), c.operator(), c.value()));
        return args;
    }
    public record Condition(String field, String operator, Object value) {
        public Condition {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("Field is required");
            if (!Set.of("=", "==", ">", ">=", "<", "<=").contains(operator)) throw new IllegalArgumentException("Invalid IDX operator");
            Objects.requireNonNull(value, "Condition value");
        }
    }
}
