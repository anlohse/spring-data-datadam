package com.anlohse.datadam.spring;

import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;

/** Lightweight IDX.INFO snapshot. keysScanned is a counter, not a completion percentage. */
public record IndexStatus(String name, DatadamIndex.Kind kind, DatadamStorage storage,
                          State state, long keysScanned, String error) {
    public enum State { BUILDING, READY, FAILED }
    public boolean ready() { return state == State.READY; }
}
