package io.datadam.demo.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.config.*;
import io.datadam.spring.diagnostics.CommandObservation;
import io.datadam.demo.examples.inventory.StockItem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.annotation.*;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(60)
class TransportFailuresIT {
    @TempDir static Path directory;
    static DisposableDatadam server;
    @BeforeAll static void start() throws Exception { server = DisposableDatadam.plain(directory); }
    @AfterAll static void stop() { if (server != null) server.close(); }
    @ParameterizedTest @EnumSource(RespFaultProxy.Fault.class)
    void lostRequestOrResponseNeverAutomaticallyRetriesMutation(RespFaultProxy.Fault fault) throws Exception {
        String key = "counter:" + UUID.randomUUID();
        try (var direct = server.pool(); var proxy = new RespFaultProxy(server.port, "JSON.NUMINCRBY", fault)) {
            var backend = new DatadamCommands(direct); backend.execute("JSON.SET", key, "$", "{\"n\":0}");
            var properties = server.properties(); properties.setPort(proxy.port()); properties.setValidateConnection(false); properties.setReadTimeout(Duration.ofMillis(100));
            var events = new ArrayList<CommandObservation>();
            try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
                var client = new DatadamCommands(pool, events::add);
                assertThatThrownBy(() -> client.execute("JSON.NUMINCRBY", key, "$.n", 1)).isInstanceOf(DataAccessResourceFailureException.class);
                assertThat(proxy.matchingRequests.get()).isEqualTo(1);
                assertThat(proxy.forwarded.get()).isEqualTo(fault == RespFaultProxy.Fault.DROP_REQUEST ? 0 : 1);
                assertThat(backend.execute("JSON.GET", key, "$.n")).isEqualTo(fault == RespFaultProxy.Fault.DROP_REQUEST ? "[0]" : "[1]");
                assertThat(events).hasSize(1); assertThat(events.get(0).outcome()).isEqualTo(CommandObservation.Outcome.CONNECTION_ERROR);
                assertThat(client.execute("PING")).isEqualTo("PONG"); // A fresh connection replaces the broken one.
            }
        }
    }
    @Test void lostVersionedSaveAcknowledgementLeavesClientVersionUnchangedAndDetectsStaleRetry() throws Exception {
        String id = UUID.randomUUID().toString();
        var model = new EntityModel<>(StockItem.class); var mapper = new ObjectMapper();
        try (var direct = server.pool(); var proxy = new RespFaultProxy(server.port, "EVAL", RespFaultProxy.Fault.DROP_REPLY)) {
            var backend = new DatadamTemplate(new DatadamCommands(direct), mapper);
            var original = backend.save(new StockItem(id, "fault-demo-" + id, 10, null), model);
            var properties = server.properties(); properties.setPort(proxy.port()); properties.setValidateConnection(false);
            try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
                var client = new DatadamTemplate(new DatadamCommands(pool), mapper);
                var update = new StockItem(id, original.sku(), 9, original.version());
                assertThatThrownBy(() -> client.save(update, model)).isInstanceOf(DataAccessResourceFailureException.class);
                assertThat(update.version()).isEqualTo(original.version()); assertThat(proxy.forwarded.get()).isEqualTo(1);
                var persisted = backend.findById(id, model).orElseThrow();
                assertThat(persisted.version()).isEqualTo(original.version() + 1); assertThat(persisted.quantity()).isEqualTo(9);
                assertThatThrownBy(() -> client.save(update, model)).isInstanceOf(OptimisticLockingFailureException.class);
                assertThat(backend.findById(id, model)).contains(persisted);
            }
        }
    }
    @Test void warmedPoolRecoversFromRestartBeforeSendingNextBusinessCommand() throws Exception {
        var p = server.properties(); p.getPool().setMaxTotal(1); p.getPool().setMaxIdle(1);
        try (var pool = new DatadamAutoConfiguration().datadamPool(p)) {
            var commands = new DatadamCommands(pool); commands.execute("JSON.SET", "restart:check", "$", "{\"n\":7}");
            assertThat(pool.getNumIdle()).isEqualTo(1); server.restart();
            assertThat(commands.execute("JSON.GET", "restart:check", "$.n")).isEqualTo("[7]");
            assertThat(commands.execute("PING")).isEqualTo("PONG");
        }
    }
    @Test void queuedBorrowerProceedsOnReleaseAndTimesOutWhenNoConnectionReturns() throws Exception {
        var p = server.properties(); p.getPool().setMaxTotal(1); p.getPool().setMaxIdle(1); p.getPool().setMaxWait(Duration.ofMillis(400));
        var executor = Executors.newSingleThreadExecutor();
        try (var pool = new DatadamAutoConfiguration().datadamPool(p)) {
            var commands = new DatadamCommands(pool);
            var held = pool.getResource();
            try {
                var waiting = executor.submit(() -> commands.execute("PING"));
                long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (pool.getNumWaiters() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertThat(pool.getNumWaiters()).isEqualTo(1); held.close(); held = null;
                assertThat(waiting.get(2, TimeUnit.SECONDS)).isEqualTo("PONG");
            } finally { if (held != null) held.close(); }
            try (var busy = pool.getResource()) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> commands.execute("PING")).isInstanceOf(DataAccessResourceFailureException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(pool.getNumWaiters()).isZero();
            }
            assertThat(commands.execute("PING")).isEqualTo("PONG");
        } finally { executor.shutdownNow(); }
    }
}
