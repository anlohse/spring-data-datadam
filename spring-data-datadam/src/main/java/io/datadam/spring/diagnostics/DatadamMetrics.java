package io.datadam.spring.diagnostics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.*;
import redis.clients.jedis.JedisPool;

/** One application pool/registry. Only bounded enum labels are recorded. */
public final class DatadamMetrics implements DatadamCommandObserver {
    private final MeterRegistry registry;
    public DatadamMetrics(MeterRegistry registry, JedisPool pool) {
        this.registry = Objects.requireNonNull(registry);
        Gauge.builder("datadam.pool.connections", pool, JedisPool::getNumActive).tag("state", "active")
            .description("Connections currently borrowed from the Datadam pool").register(registry);
        Gauge.builder("datadam.pool.connections", pool, JedisPool::getNumIdle).tag("state", "idle")
            .description("Idle connections in the Datadam pool").register(registry);
        Gauge.builder("datadam.pool.waiters", pool, JedisPool::getNumWaiters).description("Threads waiting for a pooled connection").register(registry);
        Gauge.builder("datadam.pool.max", pool, JedisPool::getMaxTotal).description("Configured maximum pooled connections").register(registry);
    }
    @Override public void completed(CommandObservation observation) {
        String operation = observation.operation().label(), outcome = observation.outcome().name().toLowerCase(java.util.Locale.ROOT);
        Timer.builder("datadam.command.duration").description("Command latency including acquisition, validation and response decoding")
            .tags("command", operation, "outcome", outcome).register(registry).record(observation.durationNanos(), TimeUnit.NANOSECONDS);
        Timer.builder("datadam.pool.acquire").description("Connection acquisition latency including creation and validation")
            .tag("outcome", observation.acquired() ? "success" : "failure").register(registry).record(observation.acquisitionNanos(), TimeUnit.NANOSECONDS);
        if (observation.outcome() != CommandObservation.Outcome.SUCCESS)
            registry.counter("datadam.command.errors", "command", operation, "outcome", outcome).increment();
    }
}
