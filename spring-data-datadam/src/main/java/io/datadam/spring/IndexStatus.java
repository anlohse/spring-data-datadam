package io.datadam.spring;

import io.datadam.spring.annotation.DatadamIndex;
import io.datadam.spring.annotation.DatadamStorage;

/** Lightweight IDX.INFO snapshot. keysScanned is a counter, not a completion percentage. */
public record IndexStatus(String name, DatadamIndex.Kind kind, DatadamStorage storage,
                          State state, long keysScanned, String error) {
    public enum State { BUILDING, READY, FAILED }
    public boolean ready() { return state == State.READY; }
}
