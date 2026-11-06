package io.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import static org.assertj.core.api.Assertions.*;

class JsonMutationTest {
    final JsonOperationsTest.FakeCommands commands = new JsonOperationsTest.FakeCommands();
    final ObjectMapper mapper = new ObjectMapper();
    final JsonPath<List<String>> tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
    DatadamJsonOperations<Note> ops() { return new DatadamTemplate(commands, mapper).json(Note.class); }
    @Test void numericRepliesPreservePromotionNullAndLegacyEnvelope() {
        commands.reply = "[9223372036854775807]";
        assertThat(ops().increment("1", JsonPath.of("$.n", Long.class), 1)).containsExactly(Long.MAX_VALUE);
        assertThat(commands.name).isEqualTo("JSON.NUMINCRBY"); assertThat(commands.args).containsExactly("note:1", "$.n", "1");
        commands.reply = "[1.5]"; assertThat(ops().multiply("1", JsonPath.of("$.n", Long.class), 0.5)).containsExactly(1.5);
        commands.reply = "[null]"; assertThat(ops().increment("1", JsonPath.of("$.n", Long.class), 1)).containsExactly((Number) null);
        commands.reply = "[]"; assertThat(ops().increment("1", JsonPath.of("$.n", Long.class), 1)).isEmpty();
        commands.reply = "5"; assertThat(ops().increment("1", JsonPath.of(".n", Long.class), 1)).containsExactly(5);
    }
    @Test void nonFiniteOperandsAreRejectedBeforeWriting() {
        for (Number value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            assertThatIllegalArgumentException().isThrownBy(() -> ops().increment("1", JsonPath.of("$.n", Long.class), value));
        assertThat(commands.calls).isZero();
    }
    @Test void appendAndInsertSendEachJsonValueAsAnArgumentIncludingNull() {
        commands.reply = List.of(3L);
        assertThat(ops().arrayAppend("1", tags, Arrays.asList("ação", null))).containsExactly(3L);
        assertThat(commands.args).containsExactly("note:1", "$.tags", "\"ação\"", "null");
        assertThat(ops().arrayInsert("1", tags, -1, List.of("x"))).containsExactly(3L);
        assertThat(commands.args).containsExactly("note:1", "$.tags", -1L, "\"x\"");
    }
    @Test void arrayCountsKeepJsonpathNilEmptyAndLegacyScalar() {
        commands.reply = Arrays.asList((Object) null);
        assertThat(ops().arrayTrim("1", tags, 0, -1)).containsExactly((Long) null);
        commands.reply = List.of(); assertThat(ops().arrayLength("1", tags)).isEmpty();
        commands.reply = 2L;
        assertThat(ops().arrayLength("1", JsonPath.of(".tags", new TypeReference<List<String>>() {}))).containsExactly(2L);
        commands.reply = null;
        assertThat(ops().arrayLength("missing", JsonPath.of(".tags", new TypeReference<List<String>>() {}))).containsExactly((Long) null);
    }
    @Test void popDistinguishesJsonNullFromNilAndDoesNotFlattenArrayElements() {
        commands.reply = Arrays.asList("null");
        assertThat(ops().arrayPop("1", tags)).containsExactly(new JsonPopResult<String>(true, null));
        commands.reply = Arrays.asList((Object) null);
        assertThat(ops().arrayPop("1", tags)).containsExactly(new JsonPopResult<String>(false, null));
        commands.reply = List.of("[1,2]");
        var nested = JsonPath.of("$.nested", new TypeReference<List<List<Integer>>>() {});
        assertThat(ops().arrayPop("1", nested)).containsExactly(new JsonPopResult<>(true, List.of(1, 2)));
        commands.reply = "\"last\"";
        assertThat(ops().arrayPop("1", JsonPath.of(".tags", new TypeReference<List<String>>() {}))).containsExactly(new JsonPopResult<>(true, "last"));
    }
    @Test void decodingAfterMutationIsDistinguishedFromServerRefusal() {
        commands.reply = List.of("123");
        assertThatThrownBy(() -> ops().arrayPop("1", tags)).isInstanceOfSatisfying(JsonMutationDecodingException.class, e -> {
            assertThat(e.getKey()).isEqualTo("note:1"); assertThat(e.getCommand()).isEqualTo("JSON.ARRPOP");
        });
        commands.failure = new DuplicateKeyException("UNIQUE");
        assertThatThrownBy(() -> ops().arrayPop("1", tags)).isSameAs(commands.failure);
    }
    @Test void emptyArrayInputsAndWrongElementTypesFailBeforeIo() {
        assertThatIllegalArgumentException().isThrownBy(() -> ops().arrayAppend("1", tags, List.of()));
        @SuppressWarnings({ "rawtypes", "unchecked" }) JsonPath<List<Object>> wrong = (JsonPath) tags;
        assertThatThrownBy(() -> ops().arrayInsert("1", wrong, 0, List.of(12))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(commands.calls).isZero();
    }
    @Test void mergePreservesNullRemovalEvenWithNonNullMapperInclusion() throws Exception {
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL); commands.reply = "OK";
        Map<String, Object> nested = new HashMap<>(); nested.put("delete", null); nested.put("keep", true);
        assertThat(ops().merge("1", JsonPath.of("$.details", Map.class), Map.of("nested", nested))).isTrue();
        assertThat(commands.name).isEqualTo("JSON.MERGE");
        assertThat(mapper.readTree(commands.args[2].toString()).get("nested").get("delete").isNull()).isTrue();
    }
    @Test void arrayIndexIsReadOnlyAndUsesExclusiveStop() {
        commands.reply = List.of(-1L);
        assertThat(ops().arrayIndex("1", tags, "x", -3, 0)).containsExactly(-1L);
        assertThat(commands.name).isEqualTo("JSON.ARRINDEX"); assertThat(commands.args).containsExactly("note:1", "$.tags", "\"x\"", -3L, 0L);
    }
    @Test void allMutationsUseTheExistingManagedAndPathGuards() {
        var versioned = new DatadamTemplate(commands, mapper).json(Managed.class);
        assertThatThrownBy(() -> versioned.increment("1", JsonPath.of("$.n", Long.class), 1)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.multiply("1", JsonPath.of("$.n", Long.class), 2)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.arrayAppend("1", tags, List.of("x"))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.arrayInsert("1", tags, 0, List.of("x"))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.arrayPop("1", tags)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.arrayTrim("1", tags, 0, 1)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> versioned.merge("1", JsonPath.of("$.details", Map.class), Map.of())).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> ops().merge("1", JsonPath.of("$", Map.class), Map.of("id", "changed")));
        assertThatThrownBy(() -> ops().merge("1", JsonPath.of("$.id", Map.class), Map.of())).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> ops().arrayPop("1", JsonPath.of("$..tags", new TypeReference<List<String>>() {})));
        assertThat(commands.calls).isZero();
    }
    @DatadamDocument(prefix = "note:") record Note(@Id String id, Long n, List<String> tags, Map<String, Object> details) {}
    @DatadamDocument(prefix = "note:") record Managed(@Id String id, @Version Long version) {}
}
