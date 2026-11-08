package io.datadam.spring.diagnostics;

import java.util.Objects;
import io.datadam.spring.DatadamCommands;
import org.springframework.boot.actuate.health.*;
import org.springframework.dao.*;

/** PING through the application's pool. Deliberately never attaches/logs exceptions or connection details. */
public final class DatadamHealthIndicator implements HealthIndicator {
    private final DatadamCommands commands;
    public DatadamHealthIndicator(DatadamCommands commands) { this.commands = Objects.requireNonNull(commands); }
    @Override public Health health() {
        try {
            return "PONG".equals(commands.execute("PING")) ? Health.up().build()
                : Health.down().withDetail("reason", "unexpected_response").build();
        } catch (PermissionDeniedDataAccessException e) {
            return Health.down().withDetail("reason", "permission").build();
        } catch (DataAccessResourceFailureException e) {
            return Health.down().withDetail("reason", "connection_or_pool").build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", "command").build();
        }
    }
}
