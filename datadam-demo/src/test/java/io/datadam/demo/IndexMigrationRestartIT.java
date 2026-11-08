package io.datadam.demo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.annotation.Id;
import redis.clients.jedis.*;
import static org.assertj.core.api.Assertions.*;

/** A separate disposable server: interruption must not disturb the application's Datadam container. */
class IndexMigrationRestartIT {
    static final int DOCUMENTS = 15000;
    @Test @Timeout(150)
    void incompleteBackfillIsResumedAfterServerRestartAndClientRecreation() throws Exception {
        String container = "datadam-migration-it-" + UUID.randomUUID();
        try {
            docker("run", "-d", "--name", container, "--cpus", "0.5", "-p", "127.0.0.1::6379", "datadam:latest", "--memcache-port", "0", "--read-threads", "2");
            String binding = docker("port", container, "6379/tcp").trim();
            int port = Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1));
            waitForServer(port);
            try (var pool = pool(port)) {
                var commands = new DatadamCommands(pool); var indexes = new DatadamIndexOperations(commands);
                indexes.ensureIndexes(Previous.class, Duration.ofSeconds(30));
                try (var jedis = pool.getResource(); var pipeline = jedis.pipelined()) {
                    for (int i = 0; i < DOCUMENTS; i++) {
                        var json = new StringBuilder("{\"id\":\"").append(i).append('"');
                        for (int field = 0; field < 16; field++) json.append(",\"f").append(field).append("\":").append(i);
                        json.append('}');
                        pipeline.sendCommand(() -> "JSON.SET".getBytes(StandardCharsets.US_ASCII), "restart:" + i, "$", json.toString());
                        if (i % 250 == 249) pipeline.sync();
                    }
                    pipeline.sync();
                }
                var migration = new IndexMigration(Target.class, "restart:v1", "restart:v2");
                var interrupted = new RestartAfterCreate(pool, container);
                assertThatThrownBy(() -> new DatadamIndexMigrations(interrupted).prepare(migration, Duration.ofSeconds(60)))
                    .isInstanceOf(DataAccessResourceFailureException.class).hasMessageContaining("simulated lost reply");
                assertThat(interrupted.beforeRestart.state()).isEqualTo(IndexStatus.State.BUILDING);
                // Docker may assign a different ephemeral host port when restarting the container.
                String resumedBinding = docker("port", container, "6379/tcp").trim();
                int resumedPort = Integer.parseInt(resumedBinding.substring(resumedBinding.lastIndexOf(':') + 1));
                waitForServer(resumedPort);
                // A new pool/client resumes from the durable definition, not in-process migration state.
                try (var resumedPool = pool(resumedPort)) {
                    var resumed = new Recording(resumedPool);
                    var report = new DatadamIndexMigrations(resumed).prepare(migration, Duration.ofSeconds(60));
                    assertThat(report.readyForUse()).isTrue();
                    // keys_scanned is reset when definitions load. This proves a BUILDING definition was
                    // resumed after restart, rather than a backfill that finished before the kill.
                    assertThat(report.target().actual().status().keysScanned()).isEqualTo(DOCUMENTS);
                    assertThat(resumed.names).doesNotContain("FT.CREATE", "IDX.CREATE", "IDX.DROP");
                    assertThat(resumed.execute("IDX.COUNT", "restart:v1")).isEqualTo((long) DOCUMENTS);
                    var total = DatadamCommands.array(resumed.execute("FT.SEARCH", "restart:v2", "@f15:[0 +inf]", "LIMIT", 0, 0));
                    assertThat(total.get(0)).isEqualTo((long) DOCUMENTS);
                }
            }
        } finally { docker("rm", "-f", "-v", container); }
    }
    static JedisPool pool(int port) {
        return new JedisPool(new HostAndPort("127.0.0.1", port), DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(5000).socketTimeoutMillis(10000).build());
    }
    static void waitForServer(int port) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try (var pool = pool(port)) { new DatadamCommands(pool).execute("PING"); return; }
            catch (DataAccessResourceFailureException e) { Thread.sleep(100); }
        }
        throw new AssertionError("Disposable Datadam did not become available");
    }
    static String docker(String... args) throws Exception {
        var command = new ArrayList<String>(); command.add("docker"); Collections.addAll(command, args);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Docker command timed out: " + args[0]); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError("Docker command failed: " + output);
        return output;
    }
    static class Recording extends DatadamCommands {
        final List<String> names = new ArrayList<>(); Recording(JedisPool pool) { super(pool); }
        @Override public Object execute(String name, Object... args) { names.add(name); return super.execute(name, args); }
    }
    static class RestartAfterCreate extends DatadamCommands {
        final String container; IndexStatus beforeRestart;
        RestartAfterCreate(JedisPool pool, String container) { super(pool); this.container = container; }
        @Override public Object execute(String name, Object... args) {
            Object result = super.execute(name, args);
            if (name.equals("FT.CREATE")) {
                beforeRestart = new DatadamIndexOperations(this).status("restart:v2");
                try { docker("kill", "--signal=STOP", container); docker("restart", "--time", "0", container); }
                catch (Exception e) { throw new DataAccessResourceFailureException("Disposable server restart failed", e); }
                throw new DataAccessResourceFailureException("simulated lost reply during restart");
            }
            return result;
        }
    }
    @DatadamDocument(prefix = "restart:")
    @DatadamIndex(name = "restart:v1", fields = @IndexField(path = "$.f0", alias = "f0", type = IndexField.Type.NUMERIC))
    record Previous(@Id String id) {}
    @DatadamDocument(prefix = "restart:")
    @DatadamIndex(name = "restart:v2", kind = DatadamIndex.Kind.SEARCH, fields = {
        @IndexField(path="$.f0",alias="f0",type=IndexField.Type.NUMERIC), @IndexField(path="$.f1",alias="f1",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f2",alias="f2",type=IndexField.Type.NUMERIC), @IndexField(path="$.f3",alias="f3",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f4",alias="f4",type=IndexField.Type.NUMERIC), @IndexField(path="$.f5",alias="f5",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f6",alias="f6",type=IndexField.Type.NUMERIC), @IndexField(path="$.f7",alias="f7",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f8",alias="f8",type=IndexField.Type.NUMERIC), @IndexField(path="$.f9",alias="f9",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f10",alias="f10",type=IndexField.Type.NUMERIC), @IndexField(path="$.f11",alias="f11",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f12",alias="f12",type=IndexField.Type.NUMERIC), @IndexField(path="$.f13",alias="f13",type=IndexField.Type.NUMERIC),
        @IndexField(path="$.f14",alias="f14",type=IndexField.Type.NUMERIC), @IndexField(path="$.f15",alias="f15",type=IndexField.Type.NUMERIC)})
    record Target(@Id String id) {}
}
