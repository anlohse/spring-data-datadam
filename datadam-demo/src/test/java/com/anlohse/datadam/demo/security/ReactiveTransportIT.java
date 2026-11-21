package com.anlohse.datadam.demo.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.output.ValueOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies RESP2 compatibility before exposing a public reactive transport. */
@Timeout(60)
class ReactiveTransportIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PREFIX = "reactive:documents:";
    private static final String KEY = PREFIX + "1";
    private static final String DOCUMENT = """
        {"name":"null","optional":null,"tags":["java"],"age":20}
        """.strip();
    private static final String IDX = "reactive:by-age";
    private static final String FT = "reactive:search";

    @TempDir static Path directory;
    private static DisposableDatadam server;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void start() throws Exception {
        server = DisposableDatadam.plain(directory);
        client = RedisClient.create();
        client.setOptions(ClientOptions.builder()
            .protocolVersion(ProtocolVersion.RESP2)
            .autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
        connection = connect(server.port);
        StepVerifier.create(dispatch(connection, () -> new ValueOutput<>(StringCodec.UTF8),
                "JSON.SET", KEY, "$", DOCUMENT))
            .expectNext("OK")
            .expectComplete().verify(TIMEOUT);

        try (var pool = server.pool()) {
            var schema = new DatadamCommands(pool);
            schema.execute("IDX.CREATE", IDX, "ON", "JSON", "PREFIX", 1, PREFIX,
                "SCHEMA", "$.age", "AS", "age", "NUMERIC", "UNIQUE");
            schema.execute("FT.CREATE", FT, "ON", "JSON", "PREFIX", 1, PREFIX,
                "SCHEMA", "$.age", "AS", "age", "NUMERIC");
            var indexes = new DatadamIndexOperations(schema);
            indexes.awaitReady(IDX, TIMEOUT);
            indexes.awaitReady(FT, TIMEOUT);
        }
    }

    @AfterAll
    static void stop() {
        try {
            if (client != null) client.shutdown();
        } finally {
            if (server != null) server.close();
        }
    }

    @Test
    void jsonDistinguishesMissingKeyFromExplicitNullAndPreservesUtf8() {
        verifyValue("JSON.GET", KEY, "$.optional").expectNext("[null]").expectComplete().verify(TIMEOUT);
        verifyValue("JSON.GET", PREFIX + "missing").expectComplete().verify(TIMEOUT);
        verifyValue("JSON.SET", "reactive:utf8", "$", "\"ação\"").expectNext("OK").expectComplete().verify(TIMEOUT);
        verifyValue("JSON.GET", "reactive:utf8").expectNext("\"ação\"").expectComplete().verify(TIMEOUT);
    }

    @Test
    void idxPreservesIntegerCountsNestedFieldsAndLookupMisses() {
        StepVerifier.create(dispatch(connection, () -> new IntegerOutput<>(StringCodec.UTF8),
                "IDX.COUNT", IDX, "age", "=", "20"))
            .expectNext(1L)
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new,
                "IDX.QUERY", IDX, "RETURNJSON", "3", "$.name", "$.optional", "$.tags"))
            .expectNext(List.of(KEY, List.of("$.name", "\"null\"", "$.optional", "null", "$.tags", "[\"java\"]")))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new, "IDX.GET", IDX, "20"))
            .assertNext(reply -> assertThat(reply).containsExactly(KEY, List.of("$", DOCUMENT)))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new, "IDX.GET", IDX, "21"))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new, "IDX.QUERY", IDX, "age", "=", "21"))
            .expectNext(List.of())
            .expectComplete().verify(TIMEOUT);
    }

    @Test
    void ftPreservesTheTotalAndProjectedAliasesInOneReply() {
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new, "FT.SEARCH", FT, "@age:[$min $max]",
                "PARAMS", "4", "min", "20", "max", "20", "DIALECT", "2",
                "RETURNJSON", "5", "$.name", "AS", "display", "$.optional", "$.missing"))
            .expectNext(List.of(1L, KEY, List.of("display", "\"null\"", "$.optional", "null")))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(dispatch(connection, NullableArrayOutput::new, "FT.SEARCH", FT, "@age:[21 21]"))
            .expectNext(List.of(0L))
            .expectComplete().verify(TIMEOUT);
    }

    @Test
    void serverErrorsReachTheSubscriberAndTheConnectionRemainsUsable() {
        verifyValue("JSON.SET", PREFIX + "duplicate", "$", DOCUMENT)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(RedisCommandExecutionException.class)
                .hasMessageContaining("UNIQUE index"))
            .verify(TIMEOUT);
        verifyValue("JSON.GET", PREFIX + "duplicate").expectComplete().verify(TIMEOUT);
        StepVerifier.create(connection.reactive().ping()).expectNext("PONG").expectComplete().verify(TIMEOUT);
    }

    @Test
    void cancellationWithoutDemandDoesNotSendAWrite() throws Exception {
        try (var proxy = new RespFaultProxy(server.port, "JSON.SET", RespFaultProxy.Fault.DROP_REQUEST);
             var proxied = connect(proxy.port())) {
            String key = "reactive:cancelled";
            var write = dispatch(proxied, () -> new ValueOutput<>(StringCodec.UTF8), "JSON.SET", key, "$", "null");
            StepVerifier.create(write, 0).expectSubscription().expectNoEvent(Duration.ofMillis(100))
                .thenCancel().verify(TIMEOUT);
            StepVerifier.create(proxied.reactive().ping()).expectNext("PONG").expectComplete().verify(TIMEOUT);
            assertThat(proxy.matchingRequests.get()).isZero();
            verifyValue("JSON.GET", key).expectComplete().verify(TIMEOUT);
        }
    }

    @Test
    void disconnectedClientDoesNotReplayAWriteAfterLosingItsReply() throws Exception {
        try (var proxy = new RespFaultProxy(server.port, "JSON.SET", RespFaultProxy.Fault.DROP_REPLY);
             var proxied = connect(proxy.port())) {
            String key = "reactive:lost-reply";
            var write = dispatch(proxied, () -> new ValueOutput<>(StringCodec.UTF8), "JSON.SET", key, "$", "42");
            StepVerifier.create(write).expectError(RedisException.class).verify(TIMEOUT);
            StepVerifier.create(write).expectError(RedisException.class).verify(TIMEOUT);
            assertThat(proxy.forwarded.get()).isEqualTo(1);
            verifyValue("JSON.GET", key).expectNext("42").expectComplete().verify(TIMEOUT);
        }
    }

    private static StatefulRedisConnection<String, String> connect(int port) throws Exception {
        var uri = RedisURI.Builder.redis("127.0.0.1", port).withTimeout(TIMEOUT).build();
        return client.connectAsync(StringCodec.UTF8, uri).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static StepVerifier.FirstStep<String> verifyValue(String command, String... arguments) {
        return StepVerifier.create(dispatch(connection, () -> new ValueOutput<>(StringCodec.UTF8), command, arguments));
    }

    private static <T> Flux<T> dispatch(StatefulRedisConnection<String, String> target,
            Supplier<CommandOutput<String, String, T>> output, String command, String... arguments) {
        return Flux.defer(() -> {
            ProtocolKeyword keyword = () -> command.getBytes(StandardCharsets.US_ASCII);
            var args = new CommandArgs<>(StringCodec.UTF8);
            for (String argument : arguments) {
                args.add(argument);
            }
            return target.reactive().dispatch(keyword, output.get(), args);
        });
    }

    /** NestedMultiOutput normally represents a top-level RESP2 nil as a list containing null. */
    private static final class NullableArrayOutput extends NestedMultiOutput<String, String> {
        private boolean array;

        private NullableArrayOutput() {
            super(StringCodec.UTF8);
        }

        @Override
        public void multi(int count) {
            array = true;
            super.multi(count);
        }

        @Override
        public void set(ByteBuffer bytes) {
            if (bytes == null && !array) {
                output = null;
            } else {
                super.set(bytes);
            }
        }
    }
}
