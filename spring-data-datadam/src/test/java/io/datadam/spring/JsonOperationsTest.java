package io.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mapping.callback.EntityCallbacks;
import static org.assertj.core.api.Assertions.*;

class JsonOperationsTest {
    final FakeCommands commands = new FakeCommands();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final DatadamTemplate template = new DatadamTemplate(commands, mapper);

    @Test void jsonpathAndLegacyWrapOnlyTheProtocolEnvelope() {
        var ops = template.json(Note.class);
        commands.reply = "[[\"a\",\"b\"]]";
        var path = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
        assertThat(ops.read("1", path).values()).containsExactly(List.of("a", "b"));
        commands.reply = "[\"a\",\"b\"]";
        assertThat(ops.read("1", JsonPath.of(".tags", new TypeReference<List<String>>() {})).values()).containsExactly(List.of("a", "b"));
        assertThat(commands.name).isEqualTo("JSON.GET"); assertThat(commands.args).containsExactly("note:1", ".tags");
    }
    @Test void missingKeyEmptyMatchesAndJsonNullAreDistinctAndImmutable() {
        var ops = template.json(Note.class); var path = JsonPath.of("$.text", String.class);
        commands.reply = null; assertThat(ops.read("1", path)).isEqualTo(new JsonReadResult<String>(false, List.of()));
        commands.reply = "[]"; assertThat(ops.read("1", path)).isEqualTo(new JsonReadResult<String>(true, List.of()));
        commands.reply = "[null]"; var result = ops.read("1", path);
        assertThat(result.keyExists()).isTrue(); assertThat(result.values()).containsExactly((String) null);
        assertThatThrownBy(() -> result.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        commands.reply = "null"; assertThat(ops.read("1", JsonPath.of(".text", String.class)).values()).containsExactly((String) null);
    }
    @Test void readsDoNotCoerceScalarTypesOrSilentlyTruncateFloats() {
        for (String value : List.of("[\"12\"]", "[1.5]", "[{}]", "[true]")) {
            commands.reply = value;
            assertThatThrownBy(() -> template.json(Note.class).read("1", JsonPath.of("$.text", Integer.class))).isInstanceOf(DataRetrievalFailureException.class);
        }
        commands.reply = "[12]";
        assertThatThrownBy(() -> template.json(Note.class).read("1", JsonPath.of("$.text", String.class))).isInstanceOf(DataRetrievalFailureException.class);
    }
    @Test void malformedReplyAndServerErrorsAreNotReportedAsMissing() {
        commands.reply = "{}";
        assertThatThrownBy(() -> template.json(Note.class).read("1", JsonPath.of("$", Map.class))).isInstanceOf(DataRetrievalFailureException.class);
        commands.reply = "not json";
        assertThatThrownBy(() -> template.json(Note.class).read("1", JsonPath.of("$", String.class))).isInstanceOf(DataRetrievalFailureException.class);
        commands.failure = new InvalidDataAccessResourceUsageException("Path does not exist");
        assertThatThrownBy(() -> template.json(Note.class).read("1", JsonPath.of(".missing", String.class))).isSameAs(commands.failure);
    }
    @Test void setEncodesValuesIncludingNullAndPreservesNativeNoOp() {
        commands.reply = "OK"; var ops = template.json(Note.class);
        assertThat(ops.set("1", JsonPath.of("$.text", String.class), "ação \"quoted\"")).isTrue();
        assertThat(commands.name).isEqualTo("JSON.SET"); assertThat(commands.args).containsExactly("note:1", "$.text", "\"ação \\\"quoted\\\"\"");
        assertThat(ops.set("1", JsonPath.of(".text", String.class), null)).isTrue(); assertThat(commands.args[2]).isEqualTo("null");
        commands.reply = null; assertThat(ops.set("1", JsonPath.of("$.missing.child", String.class), "x")).isFalse();
    }
    @Test void removeReturnsServerCountWithoutAnEntityLoad() {
        commands.reply = 1L;
        assertThat(template.json(Note.class).remove("1", JsonPath.of("$.tags[-1]", String.class))).isEqualTo(1);
        assertThat(commands.name).isEqualTo("JSON.DEL"); assertThat(commands.args).containsExactly("note:1", "$.tags[-1]");
        assertThat(commands.calls).isEqualTo(1);
    }
    @Test void rootAndMultiMatchWritesAreRejectedBeforeIo() {
        for (String path : List.of("$", ".", "$..text", "$.*", "$['a','b']", "$.a[?(@)]", "$.a[0:2]", "$.a[*]", "$.a..b", "$.a.", "$[0]", "$.a[0]b")) {
            assertThatIllegalArgumentException().isThrownBy(() -> template.json(Note.class).set("1", JsonPath.of(path, String.class), "x"));
            assertThatIllegalArgumentException().isThrownBy(() -> template.json(Note.class).remove("1", JsonPath.of(path, String.class)));
        }
        assertThat(commands.calls).isZero();
    }
    @Test void identityAndTtlAreProtectedAcrossAliasesAndEscapedPaths() {
        for (String path : List.of("$.key", ".key", "key", "$['key']", "$['k\\ey']", "$['key'].nested", "$.oldId", "$.id"))
            assertThatThrownBy(() -> template.json(Note.class).set("1", JsonPath.of(path, String.class), "x")).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> template.json(Note.class).remove("1", JsonPath.of("$.ttl", Long.class))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> template.json(Note.class).remove("1", JsonPath.of("$.oldTtl", Long.class))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(commands.calls).isZero();
    }
    @Test void versionedAuditedAndNestedAuditedModelsAreReadOnly() {
        for (Class<?> type : List.of(Versioned.class, Audited.class, NestedAudit.class)) {
            assertThatThrownBy(() -> template.json(type).set("1", JsonPath.of("$.text", String.class), "x"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("use save");
            assertThatThrownBy(() -> template.json(type).remove("1", JsonPath.of("$.text", String.class))).isInstanceOf(InvalidDataAccessApiUsageException.class);
            commands.reply = "[\"read\"]"; assertThat(template.json(type).read("1", JsonPath.of("$.text", String.class)).values()).containsExactly("read");
        }
        assertThat(commands.calls).isEqualTo(3);
    }
    @Test void opaqueManualCallbacksBlockPartialWritesAndExplicitGuardMatchesEntityType() {
        var opaque = new DatadamTemplate(commands, mapper, Map.of(), EntityCallbacks.create(), null);
        assertThatThrownBy(() -> opaque.json(Note.class).set("1", JsonPath.of("$.text", String.class), "x"))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("callbacks");
        var explicit = new DatadamTemplate(commands, mapper, Map.of(), EntityCallbacks.create(), null, type -> type == Note.class);
        assertThatThrownBy(() -> explicit.json(Note.class).remove("1", JsonPath.of("$.text", String.class))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(commands.calls).isZero();
    }
    @Test void singularQuotedNamesAndIndexesAreAccepted() {
        commands.reply = "OK";
        for (String path : List.of("$.nested.city", ".tags[0]", "$['weird.name'][\"escaped\\\"name\"]", "['tags'][-1]"))
            assertThat(template.json(Note.class).set("1", JsonPath.of(path, String.class), "x")).isTrue();
    }
    @Test void pathAndResultRejectInvalidLocalContracts() {
        assertThatIllegalArgumentException().isThrownBy(() -> JsonPath.of(" ", String.class));
        assertThatIllegalArgumentException().isThrownBy(() -> JsonPath.of("$.age", int.class));
        assertThatIllegalArgumentException().isThrownBy(() -> new JsonReadResult<>(false, List.of("x")));
    }

    @DatadamDocument(prefix = "note:") record Note(@Id @JsonProperty("key") @com.fasterxml.jackson.annotation.JsonAlias("oldId") String id, String text, List<String> tags, @DatadamTimeToLive @com.fasterxml.jackson.annotation.JsonAlias("oldTtl") Long ttl) {}
    @DatadamDocument(prefix = "note:") record Versioned(@Id String id, @Version Long version, String text) {}
    @DatadamDocument(prefix = "note:") record Audited(@Id String id, @CreatedBy String author, String text) {}
    @DatadamDocument(prefix = "note:") record NestedAudit(@Id String id, List<Audit> audits) {}
    record Audit(@CreatedBy String author) {}
    static class FakeCommands extends DatadamCommands {
        Object reply; RuntimeException failure; String name; Object[] args; int calls;
        FakeCommands() { super(null); }
        @Override public Object execute(String command, Object... args) { calls++; name = command; this.args = args; if (failure != null) throw failure; return reply; }
    }
}
