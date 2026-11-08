package io.datadam.demo;

import java.time.Duration;
import java.util.*;
import io.datadam.spring.*;
import io.datadam.spring.config.*;
import io.datadam.spring.diagnostics.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.*;
import org.springframework.dao.*;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LibraryDiagnosticsIntegrationIT {
    @Autowired DatadamTemplate template;
    @Autowired MeterRegistry registry;
    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("datadamHealthIndicator") HealthIndicator indicator;
    final List<String> keys = new ArrayList<>();
    @AfterEach void cleanup() { keys.forEach(key -> template.commands().execute("DEL", key)); }

    @Test void actuatorHealthIsUpAndDoesNotPublishConnectionDetails() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
    @Test void nativeJsonAndHashCommandsRecordSuccessAndExposePoolGauges() {
        String id = UUID.randomUUID().toString(); String json = "demo:customer:diagnostic-" + id, hash = "demo:hash:diagnostic-" + id;
        keys.add(json); keys.add(hash);
        long beforeJson = count("JSON.SET", "success"), beforeHash = count("HREPLACE", "success");
        template.commands().execute("JSON.SET", json, "$", "{\"id\":\"" + id + "\",\"name\":\"private-value\"}");
        template.commands().execute("HREPLACE", hash, "id", id, "name", "private-value");
        assertThat(count("JSON.SET", "success")).isEqualTo(beforeJson + 1); assertThat(count("HREPLACE", "success")).isEqualTo(beforeHash + 1);
        assertThat(registry.get("datadam.pool.connections").tag("state", "idle").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("datadam.pool.max").gauge().value()).isEqualTo(8);
        var response = http.getForEntity("/actuator/metrics/datadam.command.duration", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); assertThat(response.getBody()).contains("JSON.SET", "HREPLACE").doesNotContain("private-value", id);
    }
    @Test void commandErrorsAndUnknownNamesNeverExposePayloadOrIndexNames(CapturedOutput output) {
        String secret = "private-diagnostic-" + UUID.randomUUID(); String key = "demo:customer:" + secret; keys.add(key);
        long before = count("JSON.SET", "command_error"), beforeOther = count("OTHER", "command_error");
        assertThatThrownBy(() -> template.commands().execute("JSON.SET", key, "$", secret)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThatThrownBy(() -> template.commands().execute(secret)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(count("JSON.SET", "command_error")).isEqualTo(before + 1); assertThat(count("OTHER", "command_error")).isEqualTo(beforeOther + 1);
        assertThat(registry.get("datadam.command.errors").tags("command", "JSON.SET", "outcome", "command_error").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("datadam.")).map(m -> m.getId().toString()).toList().toString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }
    @Test void exhaustedPoolIsBoundedAndHealthUsesItWithoutAnExtraPool() {
        var properties = properties(); properties.getPool().setMaxTotal(1); properties.getPool().setMaxIdle(1);
        properties.getPool().setMaxWait(Duration.ofMillis(75));
        var localRegistry = new SimpleMeterRegistry();
        try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
            var commands = new DatadamCommands(pool, new DatadamMetrics(localRegistry, pool));
            var health = new DatadamHealthIndicator(commands);
            try (var held = pool.getResource()) {
                long start = System.nanoTime(); var result = health.health(); long elapsed = System.nanoTime() - start;
                assertThat(result.getStatus()).isEqualTo(Status.DOWN); assertThat(result.getDetails()).containsEntry("reason", "connection_or_pool");
                assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(2));
                assertThat(pool.getNumActive()).isEqualTo(1);
                assertThat(localRegistry.get("datadam.command.errors").tag("outcome", "pool_error").counter().count()).isEqualTo(1);
                assertThat(localRegistry.get("datadam.pool.acquire").tag("outcome", "failure").timer().count()).isEqualTo(1);
            }
            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        } finally { localRegistry.close(); }
    }
    @Test void badCredentialsYieldSanitizedHealthAndPermissionMetrics(CapturedOutput output) {
        var properties = properties(); String secret = "wrong-credential-secret-" + UUID.randomUUID(); properties.setPassword(secret);
        var localRegistry = new SimpleMeterRegistry();
        try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
            var commands = new DatadamCommands(pool, new DatadamMetrics(localRegistry, pool));
            var health = new DatadamHealthIndicator(commands).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN); assertThat(health.getDetails()).containsEntry("reason", "permission");
            assertThat(health.toString()).doesNotContain(secret, "WRONGPASS", "app");
            assertThat(localRegistry.get("datadam.command.errors").tag("outcome", "permission_error").counter().count()).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain(secret);
        } finally { localRegistry.close(); }
    }
    @Test void observerRegistryFailureCannotChangeRealCommandResult() {
        var properties = properties();
        try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
            var commands = new DatadamCommands(pool, observation -> { throw new IllegalStateException("private telemetry failure"); });
            assertThat(commands.execute("PING")).isEqualTo("PONG");
        }
    }
    long count(String command, String outcome) {
        var timer = registry.find("datadam.command.duration").tags("command", command, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }
    DatadamProperties properties() {
        var p = new DatadamProperties(); p.setHost(System.getenv().getOrDefault("DATADAM_HOST", "localhost"));
        p.setPort(Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380")));
        p.setUsername("app"); p.setPassword("local-app-password"); p.setConnectTimeout(Duration.ofMillis(300)); p.setReadTimeout(Duration.ofMillis(300));
        return p;
    }
}
