package com.anlohse.datadam.demo.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.SearchQuery;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations.Row;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.domain.Sort;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;

/** Exercises the public reactive entry points over a real RESP2 socket. */
@Timeout(30)
class ReactiveOperationsIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String IDX = "reactive:operations:by-age";
    private static final String FT = "reactive:operations:search";
    private static final String PREFIX = "reactive:operations:doc:";
    private static final String JSON = "{\"age\":20,\"name\":\"null\",\"optional\":null,\"tags\":[\"java\"]}";
    @TempDir static Path directory;
    private static DisposableDatadam server;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static ReactiveDatadamOperations operations;

    @BeforeAll
    static void start() throws Exception {
        server = DisposableDatadam.plain(directory);
        client = RedisClient.create();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        connection = connect(server.port);
        operations = new ReactiveDatadamOperations(connection);
        try (var pool = server.pool()) {
            var commands = new DatadamCommands(pool);
            for (int age : List.of(18, 20, 25)) {
                commands.execute("JSON.SET", PREFIX + age, "$", JSON.replace("20", Integer.toString(age)));
            }
            commands.execute("IDX.CREATE", IDX, "ON", "JSON", "PREFIX", 1, PREFIX,
                "SCHEMA", "$.age", "AS", "age", "NUMERIC", "UNIQUE");
            commands.execute("FT.CREATE", FT, "ON", "JSON", "PREFIX", 1, PREFIX,
                "SCHEMA", "$.age", "AS", "age", "NUMERIC", "$.name", "AS", "name", "TAG");
            var indexes = new DatadamIndexOperations(commands);
            indexes.awaitReady(IDX, TIMEOUT);
            indexes.awaitReady(FT, TIMEOUT);
            commands.execute("HREPLACE", "reactive:operations:hash:1", "age", 20, "name", "null");
            commands.execute("IDX.CREATE", "reactive:operations:hash-idx", "ON", "HASH", "PREFIX", 1,
                "reactive:operations:hash:", "SCHEMA", "age", "NUMERIC");
            commands.execute("FT.CREATE", "reactive:operations:hash-ft", "ON", "HASH", "PREFIX", 1,
                "reactive:operations:hash:", "SCHEMA", "age", "NUMERIC");
            indexes.awaitReady("reactive:operations:hash-idx", TIMEOUT);
            indexes.awaitReady("reactive:operations:hash-ft", TIMEOUT);
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
    void jsonWritesReadsAndDeletesPreserveNullAndUtf8() {
        String key = "reactive:operations:mutable";
        StepVerifier.create(operations.jsonSet(key, "$", "{\"name\":\"ação\",\"optional\":null}"))
            .expectNext(true).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.jsonGet(key, "$.name"))
            .expectNext("[\"ação\"]").expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.jsonGet(key, "$.optional"))
            .expectNext("[null]").expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.jsonDelete(key, "$")).expectNext(1L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.jsonGet(key)).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.jsonGet(PREFIX + "20")).expectNext(JSON).expectComplete().verify(TIMEOUT);
    }

    @Test
    void idxBoundsAndPredicatesUseNativeOrderingAndIndependentDecodersPerSubscription() {
        var query = CompoundQuery.all().where("age", ">=", 20).desc();
        StepVerifier.create(operations.query(IDX, query, 0, 1).repeat(1))
            .assertNext(rows -> assertThat(rows).containsExactly(new Row(PREFIX + "25", Map.of("$", JSON.replace("20", "25")))))
            .assertNext(rows -> assertThat(rows).hasSize(1))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.query(IDX, query, 1, 1))
            .assertNext(rows -> assertThat(rows).extracting(Row::key).containsExactly(PREFIX + "20"))
            .expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.count(IDX, query)).expectNext(2L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.query(IDX, query, 2, 1)).expectNext(List.of()).expectComplete().verify(TIMEOUT);
    }

    @Test
    void projectionsKeepJsonTypesAndFtReturnsItsTotalWithBoundParameters() {
        var fields = List.of("$.name", "$.optional", "$.tags", "$.missing");
        var expected = Map.of("$.name", "\"null\"", "$.optional", "null", "$.tags", "[\"java\"]");
        StepVerifier.create(operations.query(IDX, CompoundQuery.all().where("age", "=", 20), 0, 1, fields))
            .expectNext(List.of(new Row(PREFIX + "20", expected))).expectComplete().verify(TIMEOUT);
        var search = new SearchQuery("@age:[$min $max]", Map.of("min", 18, "max", 25), Sort.by("age").descending());
        StepVerifier.create(operations.search(FT, search, 1, 1, fields))
            .assertNext(page -> {
                assertThat(page.total()).isEqualTo(3);
                assertThat(page.content()).containsExactly(new Row(PREFIX + "20", expected));
            }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.search(FT, SearchQuery.of("@age:[90 99]"), 0, 1))
            .assertNext(page -> {
                assertThat(page.total()).isZero();
                assertThat(page.content()).isEmpty();
            }).expectComplete().verify(TIMEOUT);
    }

    @Test
    void nativeErrorsUseSpringExceptionsAndLeaveTheSocketUsable() {
        StepVerifier.create(operations.jsonSet(PREFIX + "duplicate", "$", JSON))
            .expectError(DuplicateKeyException.class).verify(TIMEOUT);
        StepVerifier.create(operations.jsonSet("reactive:operations:invalid", "$", "{"))
            .expectError(InvalidDataAccessResourceUsageException.class).verify(TIMEOUT);
        StepVerifier.create(operations.jsonGet(PREFIX + "20")).expectNext(JSON).expectComplete().verify(TIMEOUT);
        assertThatIllegalArgumentException().isThrownBy(() -> operations.query(IDX, CompoundQuery.all(), 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> operations.search(FT, SearchQuery.of("*"), -1, 1));
    }

    @Test
    void indexQueriesPreserveNativeHashFieldStrings() {
        var row = new Row("reactive:operations:hash:1", Map.of("age", "20", "name", "null"));
        StepVerifier.create(operations.query("reactive:operations:hash-idx", CompoundQuery.all(), 0, 1))
            .expectNext(List.of(row)).expectComplete().verify(TIMEOUT);
        StepVerifier.create(operations.search("reactive:operations:hash-ft", SearchQuery.of("*"), 0, 1,
                List.of("name")))
            .assertNext(page -> assertThat(page.content()).containsExactly(
                new Row(row.key(), Map.of("name", "null"))))
            .expectComplete().verify(TIMEOUT);
    }

    @Test
    void zeroDemandAndCancellationSendNeitherWritesNorQueryPages() throws Exception {
        try (var proxy = new RespFaultProxy(server.port, "JSON.SET", RespFaultProxy.Fault.DROP_REQUEST);
             var socket = connect(proxy.port())) {
            var reactive = new ReactiveDatadamOperations(socket);
            StepVerifier.create(reactive.jsonSet("reactive:operations:cancelled", "$", "42"), 0)
                .expectSubscription().expectNoEvent(Duration.ofMillis(100)).thenCancel().verify(TIMEOUT);
            StepVerifier.create(socket.reactive().ping()).expectNext("PONG").expectComplete().verify(TIMEOUT);
            assertThat(proxy.matchingRequests.get()).isZero();
        }
        try (var proxy = new RespFaultProxy(server.port, "IDX.QUERY", RespFaultProxy.Fault.DROP_REQUEST);
             var socket = connect(proxy.port())) {
            var reactive = new ReactiveDatadamOperations(socket);
            StepVerifier.create(reactive.query(IDX, CompoundQuery.all(), 0, 1), 0)
                .expectSubscription().expectNoEvent(Duration.ofMillis(100)).thenCancel().verify(TIMEOUT);
            StepVerifier.create(socket.reactive().ping()).expectNext("PONG").expectComplete().verify(TIMEOUT);
            assertThat(proxy.matchingRequests.get()).isZero();
        }
    }

    @Test
    void cancelAfterDispatchKeepsTheWriteAndDrainsItsReplyBeforeTheNextCommand() throws Exception {
        String key = "reactive:operations:cancel-after-send";
        try (var proxy = new RespFaultProxy(server.port, "JSON.SET", RespFaultProxy.Fault.DELAY_REPLY);
             var socket = connect(proxy.port())) {
            var reactive = new ReactiveDatadamOperations(socket);
            StepVerifier.create(reactive.jsonSet(key, "$", "42"))
                .then(() -> await().atMost(TIMEOUT).until(() -> proxy.forwarded.get() == 1))
                .thenCancel().verify(TIMEOUT);
            proxy.releaseReply.countDown();
            StepVerifier.create(reactive.jsonGet(key)).expectNext("42").expectComplete().verify(TIMEOUT);
            StepVerifier.create(reactive.jsonDelete(key, "$")).expectNext(1L).expectComplete().verify(TIMEOUT);
        }
    }

    @Test
    void lostAcknowledgmentIsNotReplayedAndClosedConnectionsFailTheSubscriber() throws Exception {
        String key = "reactive:operations:lost-reply";
        try (var proxy = new RespFaultProxy(server.port, "JSON.SET", RespFaultProxy.Fault.DROP_REPLY);
             var socket = connect(proxy.port())) {
            var reactive = new ReactiveDatadamOperations(socket);
            var write = reactive.jsonSet(key, "$", "42");
            StepVerifier.create(write).expectError(DataAccessResourceFailureException.class).verify(TIMEOUT);
            StepVerifier.create(write).expectError(DataAccessResourceFailureException.class).verify(TIMEOUT);
            assertThat(proxy.forwarded.get()).isEqualTo(1);
            StepVerifier.create(operations.jsonGet(key)).expectNext("42").expectComplete().verify(TIMEOUT);
        }
        var socket = connect(server.port);
        var reactive = new ReactiveDatadamOperations(socket);
        socket.closeAsync().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        StepVerifier.create(reactive.jsonGet(key)).expectError(DataAccessResourceFailureException.class).verify(TIMEOUT);
    }

    private static StatefulRedisConnection<String, String> connect(int port) throws Exception {
        return client.connectAsync(StringCodec.UTF8, RedisURI.Builder.redis("127.0.0.1", port)
            .withTimeout(TIMEOUT).build()).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
