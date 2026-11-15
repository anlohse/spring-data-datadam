package com.anlohse.datadam.demo.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.anlohse.datadam.demo.examples.inventory.StockItem;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.config.DatadamAutoConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import static org.assertj.core.api.Assertions.*;

/** Native protocol evidence for roadmap 9. All transaction commands use the same leased socket. */
@Timeout(30)
class NativeTransactionsIT {
    @TempDir static Path directory;
    static DisposableDatadam server;
    static JedisPool pool;

    @BeforeAll static void start() throws Exception {
        server = DisposableDatadam.plain(directory);
        pool = server.pool();
        new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(StockItem.class, Duration.ofSeconds(10));
    }

    @AfterAll static void stop() {
        try { if (pool != null) pool.close(); }
        finally { if (server != null) server.close(); }
    }

    @Test void queuedWritesAreInvisibleUntilExecAndReadsInTheBlockSeeEarlierWrites() {
        String key = key("stock");
        try (var tx = pool.getResource(); var other = pool.getResource()) {
            other.set(key, "10");
            assertThat(raw(tx, "MULTI")).isEqualTo("OK");
            assertThat(raw(tx, "SET", key, "9")).isEqualTo("QUEUED");
            assertThat(raw(tx, "GET", key)).isEqualTo("QUEUED");
            assertThat(other.get(key)).isEqualTo("10");
            assertThat(raw(tx, "EXEC")).isEqualTo(List.of("OK", "9"));
            assertThat(other.get(key)).isEqualTo("9");
        }
    }

    @Test void queueRefusalAbortsEarlierCommandsAndDiscardDropsTheQueue() {
        String key = key("order");
        try (var tx = pool.getResource()) {
            assertThat(raw(tx, "MULTI")).isEqualTo("OK");
            assertThat(raw(tx, "SET", key, "queued")).isEqualTo("QUEUED");
            assertThatThrownBy(() -> raw(tx, "SET", key)).isInstanceOf(JedisDataException.class);
            assertThatThrownBy(() -> raw(tx, "EXEC")).isInstanceOf(JedisDataException.class).hasMessageStartingWith("EXECABORT");
            assertThat(tx.get(key)).isNull();
            assertThat(raw(tx, "MULTI")).isEqualTo("OK");
            assertThat(raw(tx, "SET", key, "discarded")).isEqualTo("QUEUED");
            assertThat(raw(tx, "DISCARD")).isEqualTo("OK");
            assertThat(tx.get(key)).isNull();
        }
    }

    @Test void watchConflictsOnAWatchedWriteAndIgnoresUnrelatedWrites() {
        String stock = key("stock"), unrelated = key("unrelated");
        try (var tx = pool.getResource(); var other = pool.getResource()) {
            other.set(stock, "10");
            assertThat(raw(tx, "WATCH", stock)).isEqualTo("OK");
            assertThat(tx.get(stock)).isEqualTo("10");
            other.set(stock, "8");
            raw(tx, "MULTI");
            raw(tx, "SET", stock, "9");
            assertThat(raw(tx, "EXEC")).isNull();
            assertThat(other.get(stock)).isEqualTo("8");

            raw(tx, "WATCH", stock);
            other.set(unrelated, "independent");
            raw(tx, "MULTI");
            raw(tx, "SET", stock, "7");
            assertThat(raw(tx, "EXEC")).isEqualTo(List.of("OK"));
            assertThat(other.get(stock)).isEqualTo("7");
        }
    }

    @Test void runtimeErrorLeavesBothEarlierAndLaterWritesApplied() {
        String order = key("order"), stock = key("stock"), log = key("log");
        try (var tx = pool.getResource()) {
            tx.set(stock, "invalid-number");
            raw(tx, "MULTI");
            raw(tx, "SET", order, "accepted");
            raw(tx, "INCR", stock);
            raw(tx, "SET", log, "after-error");
            var replies = DatadamCommands.array(raw(tx, "EXEC"));
            assertThat(replies).hasSize(3);
            assertThat(replies.get(0)).isEqualTo("OK");
            assertThat(replies.get(1)).isInstanceOf(JedisDataException.class);
            assertThat(replies.get(2)).isEqualTo("OK");
            assertThat(tx.get(order)).isEqualTo("accepted");
            assertThat(tx.get(log)).isEqualTo("after-error");
        }
    }

    @Test void uniqueRejectionLeavesTheOtherWriteApplied() {
        String id = UUID.randomUUID().toString();
        String stock = "demo:examples:stock:" + id;
        String duplicate = stock + "-duplicate";
        String order = key("order");
        String json = stockJson(id, id, 10, 0);
        try (var tx = pool.getResource()) {
            assertThat(raw(tx, "JSON.SET", stock, "$", json)).isEqualTo("OK");
            raw(tx, "MULTI");
            raw(tx, "SET", order, "accepted");
            raw(tx, "JSON.SET", duplicate, "$", stockJson(id + "-duplicate", id, 3, 0));
            var replies = DatadamCommands.array(raw(tx, "EXEC"));
            assertThat(replies).hasSize(2);
            assertThat(replies.get(0)).isEqualTo("OK");
            assertThat(replies.get(1)).isInstanceOfSatisfying(JedisDataException.class,
                failure -> assertThat(failure).hasMessageContaining("UNIQUE index"));
            assertThat(tx.get(order)).isEqualTo("accepted");
            assertThat(raw(tx, "JSON.GET", duplicate)).isNull();
        }
    }

    @Test void staleVersionIsANormalZeroReplyAndDoesNotAbortAnotherWrite() throws Exception {
        String id = UUID.randomUUID().toString();
        String stock = "demo:examples:stock:" + id, order = key("order");
        String script = new ClassPathResource("com/anlohse/datadam/spring/save-versioned-json.lua")
            .getContentAsString(StandardCharsets.UTF_8);
        try (var tx = pool.getResource()) {
            assertThat(raw(tx, "JSON.SET", stock, "$", stockJson(id, id, 10, 1))).isEqualTo("OK");
            raw(tx, "MULTI");
            raw(tx, "SET", order, "accepted");
            raw(tx, "EVAL", script, "1", stock, "$.version", "0", "UPDATE", stockJson(id, id, 9, 1), "");
            assertThat(raw(tx, "EXEC")).isEqualTo(List.of("OK", 0L));
            assertThat(tx.get(order)).isEqualTo("accepted");
            assertThat(raw(tx, "JSON.GET", stock, "$.quantity")).isEqualTo("[10]");
        }
    }

    @ParameterizedTest @EnumSource(value = RespFaultProxy.Fault.class, names = {"DROP_REQUEST", "DROP_REPLY"})
    void lostExecRequestAndLostExecReplyBothLookLikeConnectionFailure(RespFaultProxy.Fault fault) throws Exception {
        String order = key("order"), stock = key("stock");
        try (var proxy = new RespFaultProxy(server.port, "EXEC", fault)) {
            var properties = server.properties();
            properties.setPort(proxy.port());
            properties.setValidateConnection(false);
            try (var client = new DatadamAutoConfiguration().datadamPool(properties);
                 var tx = client.getResource(); var other = pool.getResource()) {
                other.set(stock, "10");
                raw(tx, "MULTI");
                raw(tx, "SET", order, "accepted");
                raw(tx, "DECR", stock);
                assertThatThrownBy(() -> raw(tx, "EXEC")).isInstanceOf(JedisConnectionException.class);
                assertThat(proxy.matchingRequests.get()).isEqualTo(1);
                boolean applied = fault == RespFaultProxy.Fault.DROP_REPLY;
                assertThat(other.get(order)).isEqualTo(applied ? "accepted" : null);
                assertThat(other.get(stock)).isEqualTo(applied ? "9" : "10");
            }
        }
    }

    private static String stockJson(String id, String sku, int quantity, int version) {
        return "{\"id\":\"%s\",\"sku\":\"%s\",\"quantity\":%d,\"version\":%d}".formatted(id, sku, quantity, version);
    }

    private static String key(String kind) { return "tx:" + kind + ":" + UUID.randomUUID(); }

    private static Object raw(Jedis connection, String command, String... arguments) {
        return decode(connection.sendCommand(() -> command.getBytes(StandardCharsets.US_ASCII), arguments));
    }

    private static Object decode(Object reply) {
        if (reply instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (reply instanceof List<?> list) return list.stream().map(NativeTransactionsIT::decode).toList();
        return reply;
    }
}
