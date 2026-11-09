package com.anlohse.datadam.spring;

import java.util.*;
import java.math.BigDecimal;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anlohse.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.*;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

class HashMappingTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final JsonOperationsTest.FakeCommands commands = new JsonOperationsTest.FakeCommands();
    final DatadamTemplate template = new DatadamTemplate(commands, mapper);
    @Test void jsonRemainsDefaultAndHashIsExplicit() {
        assertThat(new EntityModel<>(Plain.class).isHash()).isTrue();
        assertThat(new EntityModel<>(JsonOperationsTest.Note.class).isHash()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> template.json(Plain.class));
        assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(Plain.class).index("anything", DatadamIndex.Kind.COMPOUND)).withMessageContaining("Undeclared");
    }
    @Test void codecRoundTripsScalarsNullEnumNestedValuesAndDecimalPrecision() throws Exception {
        var input = new Plain("id", "null", null, true, Status.ACTIVE, new BigDecimal("1234567890.123456789012300"), List.of("x", "y"), Map.of("a", List.of(1, 2)));
        var codec = new HashEntityCodec(mapper);
        var fields = codec.encode(mapper.writeValueAsString(input));
        assertThat(fields).contains("key", "id", "null", "true", "ACTIVE", "1234567890.123456789012300", "[\"x\",\"y\"]").doesNotContain("optional");
        assertThat(mapper.readValue(codec.decode(fields, Plain.class), Plain.class)).isEqualTo(input);
    }
    @Test void hashSaveUsesOneScriptAndAppliesGeneratedIdAndVersionAfterAcknowledgment() {
        commands.reply = 1L;
        var model = new EntityModel<>(Mutable.class); var input = new Mutable(); input.text = "x";
        template.save(input, model);
        assertThat(input.id).isNotBlank(); assertThat(input.version).isZero();
        assertThat(commands.name).isEqualTo("EVAL"); assertThat(commands.args[3]).isEqualTo("CREATE");
        assertThat(commands.args[4]).isEqualTo("revision"); assertThat(commands.args[6]).isEqualTo(60000L);
        assertThat(Arrays.asList(commands.args)).contains("revision", "0");
        commands.reply = 0L;
        assertThatThrownBy(() -> template.save(input, model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(input.version).isZero();
    }
    @Test void failuresDoNotAssignManagedValues() {
        var input = new Mutable(); input.text = "x"; var model = new EntityModel<>(Mutable.class);
        for (RuntimeException error : List.of(new DuplicateKeyException("UNIQUE"), new PermissionDeniedDataAccessException("ACL", null), new DataAccessResourceFailureException("transport"))) {
            commands.failure = error;
            assertThatThrownBy(() -> template.save(input, model)).isSameAs(error);
            assertThat(input.id).isNull(); assertThat(input.version).isNull();
        }
    }
    @Test void readsHashFieldsAndDeletesWithExpectedVersion() {
        commands.reply = List.of("id", "1", "revision", "9007199254740993", "text", "stored");
        var model = new EntityModel<>(Mutable.class); var loaded = template.findById("1", model).orElseThrow();
        assertThat(loaded.version).isEqualTo(9007199254740993L); assertThat(commands.name).isEqualTo("HGETALL");
        commands.reply = 1L; template.delete(loaded, model);
        assertThat(commands.args[3]).isEqualTo("DELETE"); assertThat(commands.args[5]).isEqualTo(9007199254740993L);
    }
    @Test void absentHashIsOptionalEmptyAndMalformedTypedFieldsFail() {
        commands.reply = List.of(); assertThat(template.findById("1", new EntityModel<>(Mutable.class))).isEmpty();
        commands.reply = List.of("id", "1", "revision", "invalid");
        assertThatThrownBy(() -> template.findById("1", new EntityModel<>(Mutable.class))).isInstanceOf(DataRetrievalFailureException.class);
    }
    @Test void untypedRootObjectIsRefusedBeforeWriting() {
        assertThatIllegalArgumentException().isThrownBy(() -> template.validateModel(new EntityModel<>(Ambiguous.class))).withMessageContaining("Object is ambiguous");
        assertThat(commands.calls).isZero();
    }
    @Test void temporalFieldsRespectJacksonTextAndNumericRepresentations() throws Exception {
        var input = new TemporalHash("id", java.time.Duration.ofMillis(1500), java.time.Instant.parse("2026-11-05T12:00:00.123Z"));
        var codec = new HashEntityCodec(mapper); var fields = codec.encode(mapper.writeValueAsString(input));
        assertThat(mapper.readValue(codec.decode(fields, TemporalHash.class), TemporalHash.class)).isEqualTo(input);
    }
    @Test void binaryAndPrimitiveArraysUseTheirDocumentedRepresentations() throws Exception {
        var input = new ArrayHash("id", new byte[] { 1, 2, 3, -1 }, new int[] { 2, 4 });
        var codec = new HashEntityCodec(mapper); var encoded = codec.encode(mapper.writeValueAsString(input));
        var loaded = mapper.readValue(codec.decode(encoded, ArrayHash.class), ArrayHash.class);
        assertThat(loaded.bytes()).containsExactly(input.bytes()); assertThat(loaded.values()).containsExactly(2, 4);
        assertThatIllegalArgumentException().isThrownBy(() -> template.validateModel(new EntityModel<>(CharArrayHash.class)));
    }
    enum Status { ACTIVE, INACTIVE }
    @DatadamDocument(prefix="hash:", storage= DatadamStorage.HASH)
    record Plain(@Id @JsonProperty("key") String id, String text, Integer optional, boolean enabled, Status status, BigDecimal balance, List<String> tags, Map<String, Object> nested) {}
    @DatadamDocument(prefix="hash:", storage=DatadamStorage.HASH, timeToLive=60)
    static class Mutable { @Id @DatadamGeneratedId
    public String id; @Version @JsonProperty("revision") public Long version; public String text; }
    @DatadamDocument(prefix="hash:", storage=DatadamStorage.HASH) record Ambiguous(@Id String id, Object value) {}
    @DatadamDocument(prefix="hash:", storage=DatadamStorage.HASH) record TemporalHash(@Id String id, java.time.Duration ttl, java.time.Instant instant) {}
    @DatadamDocument(prefix="hash:", storage=DatadamStorage.HASH) record ArrayHash(@Id String id, byte[] bytes, int[] values) {}
    @DatadamDocument(prefix="hash:", storage=DatadamStorage.HASH) record CharArrayHash(@Id String id, char[] value) {}
}
