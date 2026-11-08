package io.datadam.demo;

import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IndexDiagnosticsIntegrationIT {
    @Autowired DatadamTemplate template;
    @Autowired DatadamQueryDiagnostics diagnostics;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            for (var type : List.of(Customer.class, HashCustomer.class)) indexes.ensureIndexes(type, Duration.ofSeconds(30));
        }
    }
    @Test void observerCanListInspectAndCompareBothFormatsWithoutDdlOrDocumentReads() {
        try (var pool = DatadamIntegrationIT.pool("index-observer", "local-index-observer-password")) {
            var commands = new Recording(pool); var indexes = new DatadamIndexOperations(commands);
            assertThat(indexes.listIndexes()).contains("demo:customers:city-age", "demo:customers:derived", "demo:hash:customers:search");
            for (var type : List.of(Customer.class, HashCustomer.class)) {
                for (var comparison : indexes.compare(type)) {
                    assertThat(comparison.compatible()).as(comparison.describe()).isTrue();
                    assertThat(comparison.actual().status().ready()).isTrue();
                    if (comparison.actual().status().kind() == DatadamIndex.Kind.SEARCH)
                        assertThat(comparison.actual().searchStatistics().records()).isGreaterThanOrEqualTo(0);
                }
            }
            assertThat(commands.names).containsOnly("IDX.LIST", "IDX.INFO", "FT.INFO");
            assertThatThrownBy(() -> commands.execute("JSON.GET", "demo:customer:unreadable")).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> commands.execute("IDX.CREATE", "demo:diagnostics:forbidden", "SCHEMA", "city", "TAG"))
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }
    @Test void readOnlyComparisonReportsCountAndCaseDriftWithoutChangingSchema() {
        try (var pool = DatadamIntegrationIT.pool("index-observer", "local-index-observer-password")) {
            var commands = new Recording(pool); var indexes = new DatadamIndexOperations(commands);
            var before = indexes.inspect("demo:customers:city-age");
            var count = indexes.compare(WrongCount.class).get(0);
            assertThat(count.describe()).contains("fields.size: expected=1, actual=2");
            var casing = indexes.compare(WrongCase.class).get(0);
            assertThat(casing.describe()).contains("fields[0].caseSensitive: expected=false, actual=true");
            assertThat(indexes.inspect("demo:customers:city-age")).isEqualTo(before);
            assertThat(commands.names).containsOnly("IDX.INFO", "FT.INFO");
        }
    }
    @Test void absentIndexIsDistinctFromPermissionFailure() {
        try (var pool = DatadamIntegrationIT.pool("index-observer", "local-index-observer-password")) {
            var comparison = new DatadamIndexOperations(new DatadamCommands(pool)).compare(Missing.class).get(0);
            assertThat(comparison.exists()).isFalse(); assertThat(comparison.describe()).contains("exists");
        }
        assertThatThrownBy(() -> new DatadamIndexOperations(template.commands()).status("demo:customers:city-age"))
            .isInstanceOf(PermissionDeniedDataAccessException.class);
    }
    @Test void uniqueBackfillFailureExposesStateProgressAndReasonWithoutRemovingData() {
        String suffix = UUID.randomUUID().toString(); String prefix = "demo:customer:diagnostic-" + suffix + ":";
        String name = "demo:diagnostics:" + suffix;
        for (String key : List.of(prefix + "a", prefix + "b")) template.commands().execute("JSON.SET", key, "$", "{\"age\":20}");
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var commands = new DatadamCommands(pool); var indexes = new DatadamIndexOperations(commands);
            try {
                commands.execute("IDX.CREATE", name, "ON", "JSON", "PREFIX", 1, prefix, "SCHEMA", "$.age", "AS", "years", "NUMERIC", "UNIQUE");
                assertThatThrownBy(() -> indexes.awaitReady(name, Duration.ofSeconds(10))).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("failed");
                var failed = indexes.status(name);
                assertThat(failed.state()).isEqualTo(IndexStatus.State.FAILED);
                assertThat(failed.error()).isNotBlank(); assertThat(failed.keysScanned()).isGreaterThanOrEqualTo(1);
                assertThat(indexes.inspect(name).status().state()).isEqualTo(IndexStatus.State.FAILED);
                assertThat(template.commands().execute("JSON.GET", prefix + "a")).isEqualTo("{\"age\":20}");
                assertThat(template.commands().execute("JSON.GET", prefix + "b")).isEqualTo("{\"age\":20}");
            } finally { commands.execute("IDX.DROP", name); }
        } finally { template.commands().execute("DEL", prefix + "a", prefix + "b"); }
    }
    @Test void explainedIdxAndFtPlansMatchRealRepositoryCommands() throws Exception {
        try (var pool = DatadamIntegrationIT.pool("app", "local-app-password")) {
            var commands = new Recording(pool);
            var factory = new DatadamRepositoryFactoryBean<CustomerRepository, Customer, String>(CustomerRepository.class);
            factory.setTemplate(new DatadamTemplate(commands, new ObjectMapper())); factory.afterPropertiesSet();
            var repository = factory.getObject();
            String prefix = "diagnostic-" + UUID.randomUUID();
            var idx = diagnostics.explain(CustomerRepository.class, CustomerRepository.class.getMethod("findByEmailStartingWithOrderByEmailAsc", String.class, Pageable.class));
            repository.findByEmailStartingWithOrderByEmailAsc(prefix, PageRequest.of(0, 1));
            assertThat(commands.names).isEqualTo(idx.commands()); commands.names.clear();
            var ft = diagnostics.explain(CustomerRepository.class, CustomerRepository.class.getMethod("findByCityStartingWithAndAgeNotOrderByAgeAsc", String.class, int.class, Pageable.class));
            repository.findByCityStartingWithAndAgeNotOrderByAgeAsc(prefix, 20, PageRequest.of(0, 1));
            assertThat(commands.names).isEqualTo(ft.commands());
        }
    }
    @Test void statusAndWaitDoNotInvokeFtInfoOrCountRecords() {
        try (var pool = DatadamIntegrationIT.pool("index-observer", "local-index-observer-password")) {
            var commands = new Recording(pool); var indexes = new DatadamIndexOperations(commands);
            assertThat(indexes.status("demo:hash:customers:search").ready()).isTrue();
            indexes.awaitReady("demo:hash:customers:search", Duration.ofSeconds(1));
            assertThat(commands.names).containsExactly("IDX.INFO", "IDX.INFO");
        }
    }
    static class Recording extends DatadamCommands {
        final List<String> names = new ArrayList<>(); Recording(JedisPool pool) { super(pool); }
        @Override public Object execute(String command, Object... arguments) { names.add(command); return super.execute(command, arguments); }
    }
    @DatadamDocument(prefix = "demo:customer:")
    @DatadamIndex(name = "demo:customers:city-age", fields = @IndexField(path = "$.city", alias = "city"))
    record WrongCount(@Id String id) {}
    @DatadamDocument(prefix = "demo:customer:")
    @DatadamIndex(name = "demo:customers:derived", kind = DatadamIndex.Kind.SEARCH, fields = {
        @IndexField(path = "$.city", alias = "home"), @IndexField(path = "$.email", alias = "email", caseSensitive = true),
        @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
    record WrongCase(@Id String id) {}
    @DatadamDocument(prefix = "demo:customer:")
    @DatadamIndex(name = "demo:diagnostics:not-created", fields = @IndexField(path = "$.city", alias = "city"))
    record Missing(@Id String id) {}
}
