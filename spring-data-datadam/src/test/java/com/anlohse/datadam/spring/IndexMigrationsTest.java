package com.anlohse.datadam.spring;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.anlohse.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

class IndexMigrationsTest {
    final Fake commands = new Fake();
    final DatadamIndexMigrations migrations = new DatadamIndexMigrations(commands);
    final IndexMigration migration = new IndexMigration(Target.class, "old", "new:v2");
    final Duration timeout = Duration.ofSeconds(1);

    @Test void intentAndTimeoutAreValidatedBeforeIo() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IndexMigration(Target.class, "old", "new"));
        assertThatIllegalArgumentException().isThrownBy(() -> new IndexMigration(Target.class, "new:v2", "new:v2"));
        assertThatIllegalArgumentException().isThrownBy(() -> migrations.prepare(migration, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> migrations.prepare(new IndexMigration(Target.class, "old", "undeclared:v2"), timeout));
        assertThat(commands.calls).isEmpty();
    }
    @Test void preparationCreatesOnlyTargetAndNeverDropsOrRoutesAnything() {
        var report = migrations.prepare(migration, timeout);
        assertThat(report.readyForUse()).isTrue();
        assertThat(commands.creates).containsExactly("new:v2"); assertThat(commands.schemas).containsKey("old").doesNotContainKey("unrelated:v3");
        assertThat(commands.calls).containsOnly("IDX.INFO", "IDX.CREATE");
    }
    @Test void compatibleExistingTargetIsReusedWithoutDdl() {
        commands.schemas.put("new:v2", schema("ready"));
        assertThat(migrations.prepare(migration, timeout).readyForUse()).isTrue();
        assertThat(commands.calls).containsOnly("IDX.INFO"); assertThat(commands.creates).isEmpty();
    }
    @Test void existingSchemaConflictCannotReplaceEitherIndex() {
        var wrong = schema("ready"); wrong.put("unique", 1L); commands.schemas.put("new:v2", wrong);
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("unique");
        assertThat(commands.calls).containsOnly("IDX.INFO");
        assertThat(commands.schemas).containsKeys("old", "new:v2");
    }
    @Test void failedUniqueTargetRequiresExplicitDataResolutionAndCleanup() {
        commands.createdState = "failed";
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("resolve").hasMessageContaining("duplicate tuple");
        assertThat(commands.calls).doesNotContain("IDX.DROP"); assertThat(commands.schemas).containsKeys("old", "new:v2");
    }
    @Test void missingOrNonReadyPreviousCannotStartMigration() {
        commands.schemas.remove("old");
        assertThat(migrations.inspect(migration).readyForUse()).isFalse();
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("Previous index is missing");
        commands.schemas.put("old", schema("building"));
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("remain READY");
        assertThat(commands.creates).isEmpty();
    }
    @Test void differentPrefixOrStorageCannotRetireUnrelatedSource() {
        for (var mismatch : List.of(Map.entry("prefixes", (Object) List.of("unrelated:")), Map.entry("on", (Object) "HASH"))) {
            commands.schemas.put("old", schema("ready")); commands.schemas.get("old").put(mismatch.getKey(), mismatch.getValue());
            assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("same storage format and exact prefix");
        }
        assertThat(commands.creates).isEmpty();
    }
    @Test void timeoutCanBeResumedByANewClientWithoutCreatingAgain() {
        commands.createdState = "building";
        assertThatThrownBy(() -> migrations.prepare(migration, Duration.ofNanos(1))).isInstanceOf(QueryTimeoutException.class);
        assertThat(migrations.inspect(migration).readyForUse()).isFalse();
        commands.schemas.get("new:v2").put("state", "ready");
        assertThat(new DatadamIndexMigrations(commands).prepare(migration, timeout).readyForUse()).isTrue();
        assertThat(commands.creates).containsExactly("new:v2"); assertThat(commands.schemas).containsKey("old");
    }
    @Test void lostCreateReplyIsNotRetriedAutomaticallyAndNextRunInspectsIt() {
        commands.createFailure = new DataAccessResourceFailureException("reply lost after registration");
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isSameAs(commands.createFailure);
        commands.createFailure = null;
        assertThat(new DatadamIndexMigrations(commands).prepare(migration, timeout).readyForUse()).isTrue();
        assertThat(commands.creates).containsExactly("new:v2");
    }
    @Test void concurrentCreatorMustMatchTheExpectedSchema() {
        commands.createFailure = new InvalidDataAccessResourceUsageException("ERR Index already exists");
        commands.wrongCreate = true;
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("unique");
        assertThat(commands.calls).doesNotContain("IDX.DROP");
    }
    @Test void validationIsReadOnlyAndRefusesIncompleteTarget() {
        commands.schemas.put("new:v2", schema("building"));
        assertThatThrownBy(() -> migrations.validateReady(migration)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("not ready");
        assertThat(commands.calls).containsOnly("IDX.INFO");
    }
    @Test void retirementIsASeparateOperationAndDropsOnlyPrevious() {
        migrations.prepare(migration, timeout);
        assertThat(commands.drops).isEmpty();
        migrations.retirePrevious(migration);
        assertThat(commands.drops).containsExactly("old"); assertThat(commands.schemas).containsKey("new:v2").doesNotContainKey("old");
    }
    @Test void explicitFailedTargetCleanupNeverDropsReadyOrBuildingTargets() {
        for (String state : List.of("ready", "building")) {
            commands.schemas.put("new:v2", schema(state));
            assertThatThrownBy(() -> migrations.discardFailedTarget(migration)).isInstanceOf(DataIntegrityViolationException.class);
        }
        commands.schemas.put("new:v2", schema("failed")); migrations.discardFailedTarget(migration);
        assertThat(commands.drops).containsExactly("new:v2"); assertThat(commands.schemas).containsKey("old");
        migrations.prepare(migration, timeout); assertThat(commands.schemas).containsKeys("old", "new:v2");
    }
    @Test void insufficientPermissionsDoNotBecomeMissingIndexesOrTriggerDdlRetries() {
        commands.readFailure = new PermissionDeniedDataAccessException("NOPERM", null);
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isSameAs(commands.readFailure);
        assertThat(commands.creates).isEmpty(); commands.readFailure = null;
        commands.createFailure = new PermissionDeniedDataAccessException("NOPERM create", null);
        assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isSameAs(commands.createFailure);
        assertThat(commands.creates).hasSize(1); assertThat(commands.drops).isEmpty();
    }
    @Test void interruptionLeavesTargetAndPreviousAvailableForRecovery() {
        commands.createdState = "building"; Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> migrations.prepare(migration, timeout)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(commands.schemas).containsKeys("old", "new:v2"); assertThat(commands.drops).isEmpty();
        } finally { Thread.interrupted(); }
    }
    static Map<String, Object> schema(String state) {
        return new LinkedHashMap<>(Map.of("kind", "compound", "on", "JSON", "state", state, "keys_scanned", 10L,
            "prefixes", List.of("migration:"), "unique", 0L, "fields", List.of(List.of("city", "TAG", "$.city")), "error", "duplicate tuple"));
    }
    static class Fake extends DatadamCommands {
        final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>(), creates = new ArrayList<>(), drops = new ArrayList<>();
        String createdState = "ready"; RuntimeException createFailure, readFailure; boolean wrongCreate;
        Fake() { super(null); schemas.put("old", schema("ready")); }
        @Override public Object execute(String command, Object... arguments) {
            calls.add(command); String name = arguments[0].toString();
            if (command.equals("IDX.INFO")) {
                if (readFailure != null) throw readFailure;
                if (!schemas.containsKey(name)) throw new InvalidDataAccessResourceUsageException("ERR no such index '" + name + "'");
                return IndexDiagnosticsTest.flatten(schemas.get(name));
            }
            if (command.equals("IDX.CREATE")) {
                creates.add(name); schemas.put(name, schema(createdState)); if (wrongCreate) schemas.get(name).put("unique", 1L);
                if (createFailure != null) throw createFailure; return "OK";
            }
            if (command.equals("IDX.DROP")) { drops.add(name); schemas.remove(name); return "OK"; }
            throw new AssertionError(command);
        }
    }
    @DatadamDocument(prefix = "migration:")
    @DatadamIndex(name = "new:v2", fields = @IndexField(path = "$.city", alias = "city"))
    @DatadamIndex(name = "unrelated:v3", fields = @IndexField(path = "$.city", alias = "city"))
    record Target(@Id String id, String city) {}
}
