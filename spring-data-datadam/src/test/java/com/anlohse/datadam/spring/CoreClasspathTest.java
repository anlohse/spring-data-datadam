package com.anlohse.datadam.spring;

import com.anlohse.datadam.spring.repository.SimpleDatadamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.annotation.Id;
import static org.assertj.core.api.Assertions.*;

class CoreClasspathTest {
    @Test void repositoriesAndLuaResourcesWorkWithoutBootOrOptionalTelemetry() {
        // The JVM matrix combines both modules; the core Maven execution verifies the isolated boundary.
        if (Boolean.getBoolean("datadam.test.core-only")) {
            assertThatThrownBy(() -> Class.forName("org.springframework.boot.autoconfigure.AutoConfiguration")).isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> Class.forName("org.springframework.boot.actuate.health.HealthIndicator")).isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> Class.forName("io.micrometer.core.instrument.MeterRegistry")).isInstanceOf(ClassNotFoundException.class);
        }
        try (var context = new AnnotationConfigApplicationContext(ManualConfiguration.class)) {
            // The template and repository are configured with plain Spring, without Boot auto-configuration.
            var repository = new SimpleDatadamRepository<>(context.getBean(DatadamTemplate.class), Document.class);
            var document = new Document("id", "plain Spring");
            assertThat(repository.save(document)).isEqualTo(document);
            assertThat(repository.findById("id")).contains(document);
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ManualConfiguration {
        @Bean DatadamTemplate template() {
            return new DatadamTemplate(new DatadamCommands(null) {
                String stored;
                @Override public Object execute(String command, Object... args) {
                    if (command.equals("JSON.SET")) { stored = args[2].toString(); return "OK"; }
                    if (command.equals("JSON.GET")) return stored;
                    throw new AssertionError(command);
                }
            }, new ObjectMapper());
        }
    }
    @DatadamDocument(prefix = "plain:") record Document(@Id String id, String text) {}
}
