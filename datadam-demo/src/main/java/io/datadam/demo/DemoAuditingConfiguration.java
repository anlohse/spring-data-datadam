package io.datadam.demo;

import java.time.Clock;
import java.util.Optional;
import io.datadam.spring.config.EnableDatadamAuditing;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.AuditorAware;

/** Example system identity; a real application can provide its authenticated principal instead. */
@Configuration(proxyBeanMethods = false)
@EnableDatadamAuditing
public class DemoAuditingConfiguration {
    @Bean public Clock demoAuditClock() { return Clock.systemUTC(); }
    @Bean public AuditorAware<String> demoAuditor() { return () -> Optional.of("demo-application"); }
}
