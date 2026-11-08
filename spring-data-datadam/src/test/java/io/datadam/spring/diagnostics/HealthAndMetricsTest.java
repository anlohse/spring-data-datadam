package io.datadam.spring.diagnostics;

import java.util.*;
import java.util.concurrent.TimeUnit;
import io.datadam.spring.DatadamCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.*;
import org.springframework.dao.*;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class HealthAndMetricsTest {
    @Test void healthSendsOnlyPingAndDoesNotExposeUnexpectedReplies() {
        var calls = new ArrayList<String>();
        var commands = new DatadamCommands(null) { @Override public Object execute(String name, Object...args) { calls.add(name); return "private reply"; } };
        var health = new DatadamHealthIndicator(commands).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN); assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of("reason", "unexpected_response"));
        assertThat(calls).containsExactly("PING");
    }
    @Test void failuresAreSanitizedEvenWithHealthDetailsEnabledAndNoExceptionIsLogged(CapturedOutput output) {
        for (var failure : List.of(new PermissionDeniedDataAccessException("private-password", null),
            new DataAccessResourceFailureException("private-address"), new InvalidDataAccessResourceUsageException("private-value"))) {
            var commands = new DatadamCommands(null) { @Override public Object execute(String name, Object...args) { throw failure; } };
            var health = new DatadamHealthIndicator(commands).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).hasSize(1).doesNotContainKey("error"); assertThat(health.toString()).doesNotContain("private");
        }
        assertThat(output.getAll()).doesNotContain("private");
    }
    @Test void healthyPingReturnsUpWithoutConnectionMetadata() {
        var commands = new DatadamCommands(null) { @Override public Object execute(String name, Object...args) { return "PONG"; } };
        var health = new DatadamHealthIndicator(commands).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP); assertThat(health.getDetails()).isEmpty();
    }
    @Test void timersCountersAndGaugesUseFixedLabelsAndCorrectAcquisitionOutcome() {
        var pool = mock(JedisPool.class); when(pool.getNumActive()).thenReturn(2); when(pool.getNumIdle()).thenReturn(3);
        when(pool.getNumWaiters()).thenReturn(1); when(pool.getMaxTotal()).thenReturn(8);
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new DatadamMetrics(registry, pool);
            metrics.completed(new CommandObservation(CommandObservation.Operation.JSON_GET, CommandObservation.Outcome.SUCCESS, 1000, 200, true));
            metrics.completed(new CommandObservation(CommandObservation.Operation.JSON_GET, CommandObservation.Outcome.COMMAND_ERROR, 2000, 300, true));
            metrics.completed(new CommandObservation(CommandObservation.Operation.PING, CommandObservation.Outcome.POOL_ERROR, 3000, 3000, false));
            assertThat(registry.get("datadam.command.duration").tags("command", "JSON.GET", "outcome", "success").timer().count()).isEqualTo(1);
            assertThat(registry.get("datadam.command.duration").tags("command", "JSON.GET", "outcome", "success").timer().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(1000);
            assertThat(registry.get("datadam.command.errors").tag("outcome", "command_error").counter().count()).isEqualTo(1);
            assertThat(registry.find("datadam.command.errors").tag("outcome", "success").counter()).isNull();
            assertThat(registry.get("datadam.pool.acquire").tag("outcome", "success").timer().count()).isEqualTo(2);
            assertThat(registry.get("datadam.pool.acquire").tag("outcome", "failure").timer().count()).isEqualTo(1);
            assertThat(registry.get("datadam.pool.connections").tag("state", "active").gauge().value()).isEqualTo(2);
            assertThat(registry.get("datadam.pool.connections").tag("state", "idle").gauge().value()).isEqualTo(3);
            assertThat(registry.get("datadam.pool.waiters").gauge().value()).isEqualTo(1);
            assertThat(registry.get("datadam.pool.max").gauge().value()).isEqualTo(8);
        } finally { registry.close(); }
    }
}
