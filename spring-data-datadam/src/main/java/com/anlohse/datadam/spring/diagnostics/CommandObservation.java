package com.anlohse.datadam.spring.diagnostics;

import java.util.Locale;

/** Bounded, value-free telemetry. No arguments, addresses, exception messages or credentials. */
public record CommandObservation(Operation operation, Outcome outcome, long durationNanos,
                                 long acquisitionNanos, boolean acquired) {
    public enum Outcome { SUCCESS, PERMISSION_ERROR, DUPLICATE_KEY, COMMAND_ERROR, CONNECTION_ERROR, POOL_ERROR, CLIENT_ERROR }
    public enum Operation {
        PING, JSON_GET, JSON_SET, JSON_DEL, JSON_NUMINCRBY, JSON_NUMMULTBY, JSON_ARRAPPEND, JSON_ARRINSERT,
        JSON_ARRPOP, JSON_ARRTRIM, JSON_ARRLEN, JSON_ARRINDEX, JSON_MERGE, HLEN, HGET, HGETALL, HREPLACE,
        IDX_QUERY, IDX_COUNT, IDX_GET, IDX_INFO, IDX_LIST, IDX_CREATE, IDX_DROP, FT_SEARCH, FT_INFO, FT_CREATE,
        EVAL, EVALSHA, DEL, SCAN, EXPIRE, TTL, PEXPIRE, PTTL, PEXPIRETIME, PERSIST, OTHER;
        public String label() { return name().replace('_', '.'); }
        public static Operation of(String command) {
            String normalized = command.toUpperCase(Locale.ROOT);
            try {
                Operation operation = valueOf(normalized.replace('.', '_'));
                return operation.label().equals(normalized) ? operation : OTHER;
            }
            catch (IllegalArgumentException e) { return OTHER; }
        }
    }
}
