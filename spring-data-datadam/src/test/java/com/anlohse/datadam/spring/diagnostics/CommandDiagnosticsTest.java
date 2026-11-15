package com.anlohse.datadam.spring.diagnostics;

import java.util.*;
import com.anlohse.datadam.spring.DatadamCommands;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import redis.clients.jedis.*;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandDiagnosticsTest {
    final JedisPool pool = mock(JedisPool.class);
    final Jedis jedis = mock(Jedis.class);
    final List<CommandObservation> events = new ArrayList<>();
    final DatadamCommands commands = new DatadamCommands(pool, events::add);
    CommandDiagnosticsTest() { when(pool.getResource()).thenReturn(jedis); }
    @Test void successDecodesResponseAndObservesBoundedOperationWithoutValues() {
        when(jedis.sendCommand(any(ProtocolCommand.class), any(String[].class))).thenReturn("OK".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(commands.execute("json.set", "private-key", "$", "private-value")).isEqualTo("OK");
        var event = events.get(0);
        assertThat(event.operation()).isEqualTo(CommandObservation.Operation.JSON_SET);
        assertThat(event.outcome()).isEqualTo(CommandObservation.Outcome.SUCCESS); assertThat(event.acquired()).isTrue();
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(event.acquisitionNanos());
        assertThat(event.toString()).doesNotContain("private-key", "private-value"); verify(jedis).close();
    }
    @Test void nativeErrorsKeepTranslationButDoNotEnterTelemetry() {
        var errors = Map.of("ERR UNIQUE index secret-index has private-value", CommandObservation.Outcome.DUPLICATE_KEY,
            "NOPERM private-user private-key", CommandObservation.Outcome.PERMISSION_ERROR,
            "ERR private-value rejected", CommandObservation.Outcome.COMMAND_ERROR);
        for (var error : errors.entrySet()) {
            when(jedis.sendCommand(any(ProtocolCommand.class), any(String[].class))).thenThrow(new JedisDataException(error.getKey()));
            assertThatThrownBy(() -> commands.execute("JSON.SET", "private-key", "$", "private-value")).isInstanceOf(DataAccessException.class);
            assertThat(events.get(events.size() - 1).outcome()).isEqualTo(error.getValue());
        }
        assertThat(events.toString()).doesNotContain("private", "secret-index");
    }
    @Test void networkAndPoolErrorsAreDistinguishedWithoutRetry() {
        when(jedis.sendCommand(any(ProtocolCommand.class), any(String[].class))).thenThrow(new JedisConnectionException("private-address"));
        assertThatThrownBy(() -> commands.execute("PING")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(events.get(0).outcome()).isEqualTo(CommandObservation.Outcome.CONNECTION_ERROR);
        when(pool.getResource()).thenThrow(new JedisException("private-pool", new NoSuchElementException("timeout")));
        assertThatThrownBy(() -> commands.execute("PING")).isInstanceOf(DataAccessResourceFailureException.class).hasMessage("Datadam connection acquisition failed");
        assertThat(events.get(1).outcome()).isEqualTo(CommandObservation.Outcome.POOL_ERROR);
        assertThat(events.get(1).acquired()).isFalse(); verify(pool, times(2)).getResource();
    }
    @Test void authenticationDuringAcquireHasSafeTranslationAndPermissionOutcome() {
        when(pool.getResource()).thenThrow(new JedisException("private-address", new JedisAccessControlException("WRONGPASS private-password")));
        assertThatThrownBy(() -> commands.execute("PING")).isInstanceOf(PermissionDeniedDataAccessException.class)
            .hasMessage("Datadam connection authentication denied");
        assertThat(events.get(0).outcome()).isEqualTo(CommandObservation.Outcome.PERMISSION_ERROR);
        assertThat(events.toString()).doesNotContain("private");
    }
    @Test void unknownCommandNamesCannotCreateUnboundedMetricTags() {
        commands.execute("private-customer-123"); commands.execute("private-customer-456"); commands.execute("JSON_GET");
        assertThat(events).extracting(CommandObservation::operation).containsOnly(CommandObservation.Operation.OTHER);
        assertThat(events.toString()).doesNotContain("customer");
    }
    @Test void observerFailureCannotReplaceSuccessOrOriginalCommandError() {
        var broken = new DatadamCommands(pool, observation -> { throw new IllegalStateException("private observer failure"); });
        when(jedis.sendCommand(any(ProtocolCommand.class), any(String[].class))).thenReturn("PONG");
        assertThat(broken.execute("PING")).isEqualTo("PONG");
        when(jedis.sendCommand(any(ProtocolCommand.class), any(String[].class))).thenThrow(new JedisDataException("ERR original"));
        assertThatThrownBy(() -> broken.execute("PING")).isInstanceOf(InvalidDataAccessResourceUsageException.class).hasMessage("ERR original");
    }
    @Test void invalidArgumentsAreRejectedBeforeBorrowingOrObserving() {
        assertThatNullPointerException().isThrownBy(() -> commands.execute("JSON.SET", "key", null));
        verify(pool, never()).getResource(); assertThat(events).isEmpty();
    }
}
