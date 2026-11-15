package com.anlohse.datadam.demo.compatibility;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.DatadamTemplate;
import com.anlohse.datadam.spring.annotation.*;
import com.anlohse.datadam.spring.repository.DatadamRepository;
import com.anlohse.datadam.spring.repository.DatadamRepositoryFactoryBean;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.data.annotation.*;
import org.springframework.data.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Representative Spring Boot consumer executed unchanged in each supported JVM. */
public final class CompatibilityProbe {
    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(Configuration.class).web(WebApplicationType.NONE)
            .properties("datadam.host=127.0.0.1", "datadam.port=6379", "datadam.health.enabled=true", "datadam.metrics.enabled=true",
                "spring.config.location=optional:classpath:/compatibility-no-config.properties", "spring.main.banner-mode=off").run()) {
            var template = context.getBean(DatadamTemplate.class); var schema = new DatadamIndexOperations(template.commands());
            schema.ensureIndexes(JsonCustomer.class, Duration.ofSeconds(10)); schema.ensureIndexes(HashCustomer.class, Duration.ofSeconds(10));
            var json = context.getBean(JsonRepository.class); var hash = context.getBean(HashRepository.class);
            var a = json.save(new JsonCustomer(null, "Ana", "a@test", "SP", 20, null, List.of("java")));
            var b = hash.save(new HashCustomer(null, "Bia", "b@test", "SP", 30, null, List.of("hash")));
            check(a.id() != null && a.version() == 0 && b.id() != null && b.version() == 0, "generated identity/version");
            check(json.findByCityAndAgeGreaterThanEqualOrderByAgeDesc("SP", 18, PageRequest.of(0, 10)).getTotalElements() == 1, "JSON IDX page");
            check(hash.findByCityAndAgeGreaterThanEqualOrderByAgeDesc("SP", 18, PageRequest.of(0, 10)).getTotalElements() == 1, "HASH IDX page");
            check(json.summaries("SP", PageRequest.of(0, 10)).getContent().equals(List.of(new Summary("Ana", 20))), "typed JSON projection");
            check(hash.summaries("SP", PageRequest.of(0, 10)).getContent().equals(List.of(new Summary("Bia", 30))), "HASH FT projection");
            var updated = json.save(new JsonCustomer(a.id(), "Updated", a.email(), "SP", 21, a.version(), a.tags()));
            check(updated.version() == 1 && json.findById(a.id()).orElseThrow().name().equals("Updated"), "versioned update");
            try { json.save(a); throw new AssertionError("stale write accepted"); }
            catch (org.springframework.dao.OptimisticLockingFailureException expected) {}
            try { json.save(new JsonCustomer(null, "Duplicate", a.email(), "SP", 40, null, List.of())); throw new AssertionError("UNIQUE violation accepted"); }
            catch (org.springframework.dao.DuplicateKeyException expected) {}
            template.commands().execute("PEXPIRE", "compat:json:" + a.id(), 60000);
            check(((Number) template.commands().execute("PTTL", "compat:json:" + a.id())).longValue() > 0, "TTL");
            check(context.getBean("datadamHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class).health().getStatus().getCode().equals("UP"), "health");
            check(context.getBean(SimpleMeterRegistry.class).find("datadam.command.duration").timers().size() > 0, "metrics");
            json.delete(updated); hash.delete(b); check(json.findById(a.id()).isEmpty() && hash.findById(b.id()).isEmpty(), "versioned deletion");
            System.out.println("COMPATIBILITY_OK java=" + System.getProperty("java.version") + " boot=" + SpringBootVersion.getVersion()
                + " springData=" + org.springframework.data.repository.Repository.class.getPackage().getImplementationVersion());
        }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    static class Configuration {
        @Bean SimpleMeterRegistry registry() { return new SimpleMeterRegistry(); }
        @Bean
        DatadamRepositoryFactoryBean<JsonRepository, JsonCustomer, String> jsonRepository(DatadamTemplate template) {
            var factory = new DatadamRepositoryFactoryBean<JsonRepository, JsonCustomer, String>(JsonRepository.class); factory.setTemplate(template); return factory;
        }
        @Bean DatadamRepositoryFactoryBean<HashRepository, HashCustomer, String> hashRepository(DatadamTemplate template) {
            var factory = new DatadamRepositoryFactoryBean<HashRepository, HashCustomer, String>(HashRepository.class); factory.setTemplate(template); return factory;
        }
    }
    public record Summary(String name, Integer age) {}
    @DatadamDocument(prefix = "compat:json:")
    @DatadamIndex(name = "compat:json:idx", fields = {@IndexField(path="$.city",alias="city"), @IndexField(path="$.age",alias="age",type=IndexField.Type.NUMERIC)})
    @DatadamIndex(name = "compat:json:email", unique=true, fields=@IndexField(path="$.email",alias="email"))
    @DatadamIndex(name = "compat:json:ft", kind=DatadamIndex.Kind.SEARCH, fields={@IndexField(path="$.city",alias="city",caseSensitive=true), @IndexField(path="$.age",alias="age",type=IndexField.Type.NUMERIC)})
    public record JsonCustomer(@Id @DatadamGeneratedId String id, String name, String email, String city, int age, @Version Long version, List<String> tags) {}
    @DatadamDocument(prefix = "compat:hash:", storage= DatadamStorage.HASH)
    @DatadamIndex(name = "compat:hash:idx", fields = {@IndexField(path="city",alias="city"), @IndexField(path="age",alias="age",type=IndexField.Type.NUMERIC)})
    @DatadamIndex(name = "compat:hash:email", unique=true, fields=@IndexField(path="email",alias="email"))
    @DatadamIndex(name = "compat:hash:ft", kind=DatadamIndex.Kind.SEARCH, fields={@IndexField(path="city",alias="city",caseSensitive=true), @IndexField(path="age",alias="age",type=IndexField.Type.NUMERIC)})
    public record HashCustomer(@Id @DatadamGeneratedId String id, String name, String email, String city, int age, @Version Long version, List<String> tags) {}
    public interface JsonRepository extends DatadamRepository<JsonCustomer,String> {
        Page<JsonCustomer> findByCityAndAgeGreaterThanEqualOrderByAgeDesc(String city,int minimum,Pageable page);
        @FtQuery(index="compat:json:ft",value="@city:{?0}") Page<Summary> summaries(String city, Pageable page);
    }
    public interface HashRepository extends DatadamRepository<HashCustomer,String> {
        Page<HashCustomer> findByCityAndAgeGreaterThanEqualOrderByAgeDesc(String city,int minimum,Pageable page);
        @FtQuery(index="compat:hash:ft",value="@city:{?0}") Page<Summary> summaries(String city,Pageable page);
    }
}
