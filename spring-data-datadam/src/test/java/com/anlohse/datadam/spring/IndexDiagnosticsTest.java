package com.anlohse.datadam.spring;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.anlohse.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

class IndexDiagnosticsTest {
    final Fake commands = new Fake();
    final DatadamIndexOperations indexes = new DatadamIndexOperations(commands);

    @Test void listingAndPollingUseOnlyLightweightCommands() {
        assertThat(indexes.listIndexes()).containsExactly("idx", "search");
        assertThat(indexes.status("idx")).isEqualTo(new IndexStatus("idx", DatadamIndex.Kind.COMPOUND,
            DatadamStorage.JSON, IndexStatus.State.READY, 42L, null));
        assertThat(commands.calls).containsExactly("IDX.LIST", "IDX.INFO");
    }
    @Test void compoundInspectionPreservesOrderedPathsAliasesAndCaseSemantics() {
        var info = indexes.inspect("idx");
        assertThat(info.fields()).containsExactly(new IndexInfo.Field("$.city", "home", IndexField.Type.TAG, "", true));
        assertThat(info.prefixes()).containsExactly("diag:"); assertThat(info.unique()).isFalse();
        assertThat(info.searchStatistics()).isNull();
        assertThatThrownBy(() -> info.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(commands.calls).containsExactly("IDX.INFO");
    }
    @Test void hashDefaultFtSeparatorAndCaseFlagComeFromServerAttributes() {
        commands.info.put("kind", "search"); commands.info.put("on", "HASH");
        commands.info.put("fields", List.of(List.of("home", "TAG", "city", "SEPARATOR", ",")));
        commands.attributes = List.of(List.of("identifier", "city", "attribute", "home", "type", "TAG", "SEPARATOR", ",", "CASESENSITIVE"));
        var info = indexes.inspect("search");
        assertThat(info.fields()).containsExactly(new IndexInfo.Field("city", "home", IndexField.Type.TAG, ",", true));
        assertThat(info.searchStatistics()).isEqualTo(new IndexInfo.SearchStatistics(5, 12));
        assertThat(commands.calls).containsExactly("IDX.INFO", "FT.INFO");
    }
    @Test void comparisonReportsIndependentDifferencesWithoutDdl() {
        commands.info.put("on", "HASH"); commands.info.put("prefixes", List.of("other:")); commands.info.put("unique", 1L);
        commands.info.put("fields", List.of(List.of("other", "NUMERIC")));
        var comparison = indexes.compare(Doc.class, "idx");
        assertThat(comparison.exists()).isTrue(); assertThat(comparison.compatible()).isFalse();
        assertThat(comparison.differences()).extracting(IndexSchemaComparison.Difference::property)
            .contains("storage", "prefixes", "unique", "fields[0].path", "fields[0].alias", "fields[0].type", "fields[0].caseSensitive");
        assertThat(comparison.describe()).contains("fields[0].path: expected=$.city, actual=other", "unique: expected=false, actual=true");
        assertThat(commands.calls).containsExactly("IDX.INFO");
    }
    @Test void fieldCountAndOrderArePartOfCompatibility() {
        commands.info.put("fields", List.of());
        assertThat(indexes.compare(Doc.class).get(0).differences()).extracting(IndexSchemaComparison.Difference::property).containsExactly("fields.size");
        commands.info.put("fields", List.of(List.of("second", "NUMERIC", "$.age"), List.of("home", "TAG", "$.city")));
        assertThat(indexes.compare(Doc.class).get(0).describe()).contains("fields.size", "fields[0].path");
    }
    @Test void schemaCompatibilityDoesNotImplyReady() {
        commands.info.put("state", "building");
        var comparison = indexes.compare(Doc.class, "idx");
        assertThat(comparison.compatible()).isTrue(); assertThat(comparison.actual().status().ready()).isFalse();
    }
    @Test void missingIndexIsReportedButAclAndOtherErrorsPropagate() {
        commands.failure = new InvalidDataAccessResourceUsageException("ERR no such index 'idx'");
        var comparison = indexes.compare(Doc.class, "idx");
        assertThat(comparison.exists()).isFalse(); assertThat(comparison.describe()).contains("exists: expected=true, actual=false");
        for (var failure : List.of(new PermissionDeniedDataAccessException("NOPERM", null), new DataAccessResourceFailureException("offline"),
            new InvalidDataAccessResourceUsageException("different error"))) {
            commands.failure = failure;
            assertThatThrownBy(() -> indexes.compare(Doc.class, "idx")).isSameAs(failure);
        }
    }
    @Test void awaitReadyTracksProgressAndFailedBackfill() {
        commands.states.addAll(List.of("building", "ready")); indexes.awaitReady("idx", Duration.ofSeconds(1));
        assertThat(commands.calls).containsExactly("IDX.INFO", "IDX.INFO");
        commands.info.put("state", "failed"); commands.info.put("error", "duplicate tuple");
        assertThatThrownBy(() -> indexes.awaitReady("idx", Duration.ofSeconds(1)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("duplicate tuple");
    }
    @Test void timeoutIncludesProgressAndInvalidTimeoutCannotCreateIndexes() {
        commands.info.put("state", "building");
        assertThatThrownBy(() -> indexes.awaitReady("idx", Duration.ofMillis(1))).isInstanceOf(QueryTimeoutException.class).hasMessageContaining("keys scanned=42");
        commands.calls.clear();
        assertThatIllegalArgumentException().isThrownBy(() -> indexes.ensureIndexes(Doc.class, Duration.ZERO));
        assertThat(commands.calls).isEmpty();
    }
    @Test void waitPreservesInterruption() {
        commands.info.put("state", "building"); Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> indexes.awaitReady("idx", Duration.ofSeconds(1))).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void malformedOrInconsistentMetadataIsNotAcceptedAsCompatible() {
        commands.info.put("state", "unknown");
        assertThatThrownBy(() -> indexes.inspect("idx")).isInstanceOf(DataRetrievalFailureException.class);
        commands.info.put("state", "ready"); commands.info.put("kind", "search");
        commands.attributes = List.of(List.of("identifier", "$.wrong", "attribute", "home", "type", "TAG", "SEPARATOR", ""));
        assertThatThrownBy(() -> indexes.inspect("idx")).isInstanceOf(DataRetrievalFailureException.class).hasMessageContaining("disagrees");
    }
    @Test void undeclaredIndexIsRejectedBeforeAnyIo() {
        assertThatIllegalArgumentException().isThrownBy(() -> indexes.compare(Doc.class, "other"));
        assertThat(commands.calls).isEmpty();
    }
    static class Fake extends DatadamCommands {
        final List<String> calls = new ArrayList<>(); final Queue<String> states = new ArrayDeque<>();
        final Map<String, Object> info = new LinkedHashMap<>(Map.of("kind", "compound", "on", "JSON", "state", "ready", "keys_scanned", 42L,
            "prefixes", List.of("diag:"), "unique", 0L, "fields", List.of(List.of("home", "TAG", "$.city"))));
        List<?> attributes; RuntimeException failure;
        Fake() { super(null); }
        @Override public Object execute(String command, Object... arguments) {
            calls.add(command); if (failure != null) throw failure;
            if (command.equals("IDX.LIST")) return List.of("idx", "search");
            if (command.equals("IDX.INFO")) {
                if (!states.isEmpty()) info.put("state", states.remove());
                return flatten(info);
            }
            if (command.equals("FT.INFO")) return List.of("attributes", attributes, "num_docs", 5L, "num_records", 12L);
            throw new AssertionError("Unexpected mutation/command: " + command);
        }
    }
    static List<Object> flatten(Map<String, Object> values) {
        var result = new ArrayList<>(); values.forEach((key, value) -> { result.add(key); result.add(value); }); return result;
    }
    @DatadamDocument(prefix = "diag:")
    @DatadamIndex(name = "idx", fields = @IndexField(path = "$.city", alias = "home"))
    record Doc(@Id String id, String city) {}
}
