package com.anlohse.datadam.spring;

import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactiveOperationsConfigurationTest {
    @Test
    @SuppressWarnings("unchecked")
    void rejectsConnectionsThatCanNegotiateResp3OrQueueAndReplayWrites() {
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var configurations = new ClientOptions[] {
            ClientOptions.builder().protocolVersion(ProtocolVersion.RESP3).autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build(),
            ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build(),
            ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.ACCEPT_COMMANDS).build()
        };
        for (var configuration : configurations) {
            when(connection.getOptions()).thenReturn(configuration);
            assertThatIllegalArgumentException().isThrownBy(() -> new ReactiveDatadamOperations(connection));
        }
    }
}
