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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IndexMigrationsIntegrationIT {
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final List<String> NAMES = List.of("demo:migration:json:v1", "demo:migration:json:v2", "demo:migration:unique:v2", "demo:migration:hash:v1", "demo:migration:hash:v2");
    @Autowired DatadamTemplate template;
    final List<String> keys = new ArrayList<>();
    final IndexMigration json = new IndexMigration(JsonV2.class, "demo:migration:json:v1", "demo:migration:json:v2");
    final IndexMigration hash = new IndexMigration(HashV2.class, "demo:migration:hash:v1", "demo:migration:hash:v2");
    @BeforeEach void setup() {
        dropFixtures();
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            indexes.ensureIndexes(LegacyJson.class, TIMEOUT); indexes.ensureIndexes(LegacyHash.class, TIMEOUT);
        }
    }
    @AfterEach void cleanup() { dropFixtures(); keys.forEach(k -> template.commands().execute("DEL", k)); }
    void dropFixtures() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var commands = new DatadamCommands(pool); var names = new DatadamIndexOperations(commands).listIndexes();
            NAMES.stream().filter(names::contains).forEach(name -> commands.execute("IDX.DROP", name));
        }
    }
    String json(String email, int age) {
        String id = UUID.randomUUID().toString(), key = "demo:customer:migration:" + id; keys.add(key);
        template.commands().execute("JSON.SET", key, "$", "{\"id\":\"" + id + "\",\"city\":\"SP\",\"age\":" + age + ",\"email\":\"" + id + "-" + email + "\"}");
        return key;
    }
    String hash(int age) {
        String id = UUID.randomUUID().toString(), key = "demo:hash:migration:" + id; keys.add(key);
        template.commands().execute("HREPLACE", key, "id", id, "city", "SP", "age", age); return key;
    }
    @Test void jsonPreparationBackfillsAndRepeatedRunsRetainBothIndexes() {
        json("a@test", 20); json("b@test", 30);
        try (var pool = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password")) {
            var migrations = new DatadamIndexMigrations(new DatadamCommands(pool));
            assertThat(migrations.prepare(json, TIMEOUT).readyForUse()).isTrue();
            assertThat(migrations.prepare(json, TIMEOUT).readyForUse()).isTrue();
            assertThat(migrations.validateReady(json).previous().status().ready()).isTrue();
            assertThat(template.commands().execute("IDX.COUNT", json.previousIndex(), "city", "=", "SP")).isEqualTo(2L);
            assertThat(template.commands().execute("IDX.COUNT", json.targetIndex(), "city", "=", "SP", "age", ">=", 20)).isEqualTo(2L);
        }
    }
    @Test void hashSearchTargetKeepsNativeAliasCaseAndFullHistoricalContent() {
        String first = hash(20), second = hash(30);
        try (var pool = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password")) {
            var result = new DatadamIndexMigrations(new DatadamCommands(pool)).prepare(hash, TIMEOUT);
            assertThat(result.target().actual().fields().get(0)).isEqualTo(new IndexInfo.Field("city", "home", IndexField.Type.TAG, ",", true));
            var reply = DatadamCommands.array(template.commands().execute("FT.SEARCH", hash.targetIndex(), "@home:{SP} @years:[20 30]", "NOCONTENT"));
            assertThat(reply.get(0)).isEqualTo(2L); assertThat(reply.stream().map(Object::toString).toList()).contains(first, second);
            assertThat(template.commands().execute("IDX.COUNT", hash.previousIndex())).isEqualTo(2L);
        }
    }
    @Test void uniqueFailureRequiresDataFixAndExplicitDiscardBeforeRecreation() {
        json("duplicate@test", 20); String fix = json("duplicate@test", 30);
        var migration = new IndexMigration(UniqueV2.class, json.previousIndex(), "demo:migration:unique:v2");
        try (var preparer = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password");
             var cleanup = DatadamIntegrationIT.pool("index-retirer", "local-index-retirer-password")) {
            var migrations = new DatadamIndexMigrations(new DatadamCommands(preparer));
            assertThatThrownBy(() -> migrations.prepare(migration, TIMEOUT)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("resolve");
            assertThat(migrations.inspect(migration).target().actual().status().state()).isEqualTo(IndexStatus.State.FAILED);
            assertThat(template.commands().execute("IDX.COUNT", json.previousIndex())).isEqualTo(2L);
            assertThatThrownBy(() -> migrations.discardFailedTarget(migration)).isInstanceOf(PermissionDeniedDataAccessException.class);
            template.commands().execute("JSON.SET", fix, "$.city", "\"RJ\"");
            new DatadamIndexMigrations(new DatadamCommands(cleanup)).discardFailedTarget(migration);
            assertThat(migrations.prepare(migration, TIMEOUT).readyForUse()).isTrue();
            assertThat(template.commands().execute("IDX.COUNT", migration.targetIndex())).isEqualTo(2L);
        }
    }
    @Test void retirementNeedsSeparateCredentialsAndNeverDeletesDocuments() {
        String key = json("a@test", 20); Object before = template.commands().execute("JSON.GET", key);
        try (var preparer = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password");
             var cleanup = DatadamIntegrationIT.pool("index-retirer", "local-index-retirer-password")) {
            var migrations = new DatadamIndexMigrations(new DatadamCommands(preparer)); migrations.prepare(json, TIMEOUT);
            assertThatThrownBy(() -> migrations.retirePrevious(json)).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThat(migrations.inspect(json).previous()).isNotNull();
            new DatadamIndexMigrations(new DatadamCommands(cleanup)).retirePrevious(json);
            assertThat(migrations.inspect(json).previous()).isNull();
            assertThat(template.commands().execute("JSON.GET", key)).isEqualTo(before);
            assertThat(template.commands().execute("IDX.COUNT", json.targetIndex())).isEqualTo(1L);
        }
    }
    @Test void collidingTargetSchemaDoesNotReplaceIndexesOrData() {
        String key = json("a@test", 20); Object before = template.commands().execute("JSON.GET", key);
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var commands = new DatadamCommands(pool);
            commands.execute("IDX.CREATE", json.targetIndex(), "ON", "JSON", "PREFIX", 1, "demo:customer:migration:", "SCHEMA", "$.age", "AS", "wrong", "NUMERIC");
            new DatadamIndexOperations(commands).awaitReady(json.targetIndex(), TIMEOUT);
            assertThatThrownBy(() -> new DatadamIndexMigrations(commands).prepare(json, TIMEOUT))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fields");
            assertThat(new DatadamIndexOperations(commands).listIndexes()).contains(json.previousIndex(), json.targetIndex());
            assertThat(template.commands().execute("JSON.GET", key)).isEqualTo(before);
        }
    }
    @Test void stagingDeclarationCannotChangeActiveDerivedRepositorySelection() throws Exception {
        json("a@test", 20);
        var diagnostics = new DatadamQueryDiagnostics(template);
        var method = LegacyRepository.class.getMethod("findByCity", String.class);
        assertThat(diagnostics.explain(LegacyRepository.class, method).index()).isEqualTo(json.previousIndex());
        try (var pool = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password")) {
            new DatadamIndexMigrations(new DatadamCommands(pool)).prepare(json, TIMEOUT);
        }
        assertThat(diagnostics.explain(LegacyRepository.class, method).index()).isEqualTo(json.previousIndex());
        var factory = new DatadamRepositoryFactoryBean<LegacyRepository, LegacyJson, String>(LegacyRepository.class);
        factory.setTemplate(template); factory.afterPropertiesSet();
        assertThat(factory.getObject().findByCity("SP")).hasSize(1);
    }
    @Test void observerCannotPrepareAndPreparerCannotReadApplicationDocuments() {
        String key = json("a@test", 20);
        try (var observer = DatadamIntegrationIT.pool("index-observer", "local-index-observer-password");
             var preparer = DatadamIntegrationIT.pool("index-migrator", "local-index-migrator-password")) {
            var migration = new DatadamIndexMigrations(new DatadamCommands(observer));
            assertThatThrownBy(() -> migration.prepare(json, TIMEOUT)).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThat(migration.inspect(json).target().exists()).isFalse();
            assertThatThrownBy(() -> new DatadamCommands(preparer).execute("JSON.GET", key)).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }
    @DatadamDocument(prefix = "demo:customer:migration:")
    @DatadamIndex(name = "demo:migration:json:v1", fields = @IndexField(path = "$.city", alias = "city"))
    record LegacyJson(@Id String id, String city, int age, String email) {}
    @DatadamDocument(prefix = "demo:customer:migration:")
    @DatadamIndex(name = "demo:migration:json:v2", fields = {@IndexField(path = "$.city", alias = "city"), @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
    record JsonV2(@Id String id) {}
    @DatadamDocument(prefix = "demo:customer:migration:")
    @DatadamIndex(name = "demo:migration:unique:v2", unique = true, fields = @IndexField(path = "$.city", alias = "city"))
    record UniqueV2(@Id String id) {}
    @DatadamDocument(prefix = "demo:hash:migration:", storage = DatadamStorage.HASH)
    @DatadamIndex(name = "demo:migration:hash:v1", fields = @IndexField(path = "city", alias = "city"))
    record LegacyHash(@Id String id) {}
    @DatadamDocument(prefix = "demo:hash:migration:", storage = DatadamStorage.HASH)
    @DatadamIndex(name = "demo:migration:hash:v2", kind = DatadamIndex.Kind.SEARCH, fields = {
        @IndexField(path = "city", alias = "home", caseSensitive = true), @IndexField(path = "age", alias = "years", type = IndexField.Type.NUMERIC)})
    record HashV2(@Id String id) {}
    interface LegacyRepository extends DatadamRepository<LegacyJson, String> { List<LegacyJson> findByCity(String city); }
}
