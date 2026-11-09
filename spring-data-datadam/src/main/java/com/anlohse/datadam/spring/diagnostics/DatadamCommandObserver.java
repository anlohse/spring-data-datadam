package com.anlohse.datadam.spring.diagnostics;

/** An optional observer; runtime failures in an observer never replace a command result. */
@FunctionalInterface
public interface DatadamCommandObserver {
    DatadamCommandObserver NONE = observation -> {};
    void completed(CommandObservation observation);
}
