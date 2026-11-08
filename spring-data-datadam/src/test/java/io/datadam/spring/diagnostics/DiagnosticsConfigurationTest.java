package io.datadam.spring.diagnostics;

import java.time.Duration;
import io.datadam.spring.*;
import io.datadam.spring.config.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticsConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(DatadamAutoConfiguration.class, DatadamDiagnosticsAutoConfiguration.class));
    @Test void defaultsKeepLegacyTimeoutAndBoundedPoolWithoutOptionalDiagnostics() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DatadamMetrics.class).doesNotHaveBean("datadamHealthIndicator");
            var pool = context.getBean("datadamPool", JedisPool.class); var properties = context.getBean(DatadamProperties.class);
            assertThat(pool.getMaxTotal()).isEqualTo(8); assertThat(pool.getMaxIdle()).isEqualTo(8); assertThat(pool.getMinIdle()).isZero();
            assertThat(pool.getMaxWaitDuration()).isEqualTo(Duration.ofSeconds(5)); assertThat(pool.getTestOnBorrow()).isTrue();
            assertThat(properties.effectiveConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.effectiveReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }
    @Test void independentTimeoutsAndPoolOptionsAreBoundToActualPool() {
        runner.withPropertyValues("datadam.timeout=7s", "datadam.connect-timeout=120ms", "datadam.read-timeout=300ms",
            "datadam.pool.max-wait=80ms", "datadam.pool.max-total=3", "datadam.pool.max-idle=2", "datadam.pool.min-idle=1",
            "datadam.pool.block-when-exhausted=false", "datadam.validate-connection=false", "datadam.pool.test-while-idle=true", "datadam.pool.eviction-interval=30s")
            .run(context -> {
                assertThat(context).hasNotFailed(); var pool = context.getBean("datadamPool", JedisPool.class);
                assertThat(pool.getMaxTotal()).isEqualTo(3); assertThat(pool.getMaxIdle()).isEqualTo(2); assertThat(pool.getMinIdle()).isEqualTo(1);
                assertThat(pool.getMaxWaitDuration()).isEqualTo(Duration.ofMillis(80)); assertThat(pool.getBlockWhenExhausted()).isFalse();
                assertThat(pool.getTestOnBorrow()).isFalse(); assertThat(pool.getTestWhileIdle()).isTrue();
                assertThat(pool.getDurationBetweenEvictionRuns()).isEqualTo(Duration.ofSeconds(30));
                var properties = context.getBean(DatadamProperties.class);
                assertThat(properties.effectiveConnectTimeout()).isEqualTo(Duration.ofMillis(120)); assertThat(properties.effectiveReadTimeout()).isEqualTo(Duration.ofMillis(300));
            });
    }
    @Test void legacyTimeoutStillSuppliesAllThreeLimits() {
        runner.withPropertyValues("datadam.timeout=900ms").run(context -> {
            var properties = context.getBean(DatadamProperties.class);
            assertThat(properties.effectiveConnectTimeout()).isEqualTo(Duration.ofMillis(900));
            assertThat(properties.effectiveReadTimeout()).isEqualTo(Duration.ofMillis(900));
            assertThat(context.getBean("datadamPool", JedisPool.class).getMaxWaitDuration()).isEqualTo(Duration.ofMillis(900));
        });
    }
    @Test void invalidDurationsAndPoolSizesFailBeforeUse() {
        for (String property : java.util.List.of("datadam.timeout=0", "datadam.connect-timeout=-1ms", "datadam.read-timeout=1ns",
            "datadam.pool.max-wait=-1ms", "datadam.pool.max-total=0", "datadam.pool.min-idle=9", "datadam.pool.max-idle=-1",
            "datadam.pool.eviction-interval=-1ms", "datadam.port=65536", "datadam.read-timeout=1000000000000d")) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }
    @Test void optionalClassesMayBeAbsentEvenWithFlagsEnabled() {
        runner.withClassLoader(new FilteredClassLoader("io.micrometer", "org.springframework.boot.actuate"))
            .withPropertyValues("datadam.health.enabled=true", "datadam.metrics.enabled=true")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("datadamHealthIndicator").doesNotHaveBean("datadamMetrics"));
    }
    @Test void healthCanBeEnabledIndependentlyOfMetricsAndRegistry() {
        var commands = new DatadamCommands(null) { @Override public Object execute(String command, Object... args) { return "PONG"; } };
        runner.withBean(DatadamCommands.class, () -> commands).withPropertyValues("datadam.health.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed().hasBean("datadamHealthIndicator").doesNotHaveBean(DatadamMetrics.class);
                assertThat(context.getBean("datadamHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class).health().getStatus().getCode()).isEqualTo("UP");
            });
    }
    @Test void metricsNeedBothExplicitOptInAndRegistry() {
        runner.withPropertyValues("datadam.metrics.enabled=true").run(context -> assertThat(context).doesNotHaveBean(DatadamMetrics.class));
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> assertThat(context).doesNotHaveBean(DatadamMetrics.class));
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).withPropertyValues("datadam.metrics.enabled=true")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DatadamMetrics.class).hasSingleBean(DatadamCommandObserver.class));
    }
    @Test void customPoolObserverAndHealthBeanAreRespected() {
        var observer = (DatadamCommandObserver) event -> {};
        var health = (org.springframework.boot.actuate.health.HealthIndicator) () -> org.springframework.boot.actuate.health.Health.up().build();
        var pool = mock(JedisPool.class);
        runner.withBean("datadamPool", JedisPool.class, () -> pool).withBean(DatadamCommandObserver.class, () -> observer)
            .withBean("datadamHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class, () -> health)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new).withPropertyValues("datadam.health.enabled=true", "datadam.metrics.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(DatadamMetrics.class);
                assertThat(context.getBean("datadamPool")).isSameAs(pool); assertThat(context.getBean(DatadamCommandObserver.class)).isSameAs(observer);
                assertThat(context.getBean("datadamHealthIndicator")).isSameAs(health);
            });
    }
}
