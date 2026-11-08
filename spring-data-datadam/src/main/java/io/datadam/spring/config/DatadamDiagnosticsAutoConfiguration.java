package io.datadam.spring.config;

import io.datadam.spring.DatadamCommands;
import io.datadam.spring.diagnostics.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

@AutoConfiguration(after = DatadamAutoConfiguration.class, afterName = {
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"})
public class DatadamDiagnosticsAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnProperty(prefix = "datadam.health", name = "enabled", havingValue = "true")
    static class HealthConfiguration {
        @Bean @ConditionalOnBean(DatadamCommands.class) @ConditionalOnMissingBean(name = "datadamHealthIndicator")
        org.springframework.boot.actuate.health.HealthIndicator datadamHealthIndicator(DatadamCommands commands) {
            return new DatadamHealthIndicator(commands);
        }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(prefix = "datadam.metrics", name = "enabled", havingValue = "true")
    static class MetricsConfiguration {
        @Bean @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        @ConditionalOnMissingBean(DatadamCommandObserver.class)
        DatadamMetrics datadamMetrics(io.micrometer.core.instrument.MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Qualifier("datadamPool") redis.clients.jedis.JedisPool pool) {
            return new DatadamMetrics(registry, pool);
        }
    }
}
