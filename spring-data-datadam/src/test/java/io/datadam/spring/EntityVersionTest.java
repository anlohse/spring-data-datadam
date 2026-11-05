package io.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.annotation.*;
import io.datadam.spring.callback.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mapping.callback.EntityCallbacks;
import static org.assertj.core.api.Assertions.*;

class EntityVersionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecordingCommands commands = new RecordingCommands();
    private DatadamTemplate template() { return new DatadamTemplate(commands, mapper); }
    private final EntityModel<Versioned> model = new EntityModel<>(Versioned.class);

    @Test void nullableVersionDeterminesNewStateEvenWithAssignedIdAndStartsAtZero() {
        var input = new Versioned("provided", null, "text");
        assertThat(model.isNew(input)).isTrue();
        var saved = template().save(input, model);
        assertThat(saved.version()).isZero();
        assertThat(input.version()).isNull();
        assertThat(saved.id()).isEqualTo("provided");
        assertThat(model.isNew(saved)).isFalse();
        assertThat(commands.args[5]).isEqualTo("CREATE");
    }
    @Test void primitiveVersionsStartAtOneAndWrapperZeroIsAnExistingVersion() {
        var longs = new EntityModel<>(PrimitiveLong.class);
        assertThat(longs.isNew(new PrimitiveLong("id", 0))).isTrue();
        assertThat(template().save(new PrimitiveLong("id", 0), longs).version()).isEqualTo(1);
        var ints = new EntityModel<>(PrimitiveInt.class);
        assertThat(template().save(new PrimitiveInt("id", 0), ints).version()).isEqualTo(1);
        var integer = new EntityModel<>(BoxedInt.class);
        assertThat(template().save(new BoxedInt("id", null), integer).version()).isZero();
        assertThat(template().save(new BoxedInt("id", 0), integer).version()).isEqualTo(1);
    }
    @Test void updateWritesExpectedAndNextVersionWithoutMutatingTheRecord() throws Exception {
        var input = new Versioned("id", 4L, "text");
        var saved = template().save(input, model);
        assertThat(saved.version()).isEqualTo(5);
        assertThat(input.version()).isEqualTo(4);
        assertThat(commands.args[4]).isEqualTo(4L);
        assertThat(commands.args[5]).isEqualTo("UPDATE");
        assertThat(mapper.readTree(commands.args[6].toString()).get("version").longValue()).isEqualTo(5);
    }
    @Test void mutableVersionChangesOnlyAfterSuccess() {
        var value = new Mutable(); value.id = "id"; value.version = 3L;
        commands.beforeReply = () -> assertThat(value.version).isEqualTo(3);
        assertThat(template().save(value, new EntityModel<>(Mutable.class))).isSameAs(value);
        assertThat(value.version).isEqualTo(4);
    }
    @Test void numericStringSerializationCannotSilentlyBreakTheVersionProtocol() {
        mapper.getFactory().configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new Versioned("id", null, "text"), model))
            .withMessageContaining("exact JSON integer");
        assertThat(commands.calls).isZero();
    }
    @Test void conflictUniqueAndTransportFailureDoNotAdvanceMutableVersionOrAssignId() {
        var model = new EntityModel<>(Mutable.class);
        var value = new Mutable();
        commands.reply = 0L;
        assertThatThrownBy(() -> template().save(value, model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(value.id).isNull(); assertThat(value.version).isNull();
        commands.failure = new DuplicateKeyException("UNIQUE failure");
        assertThatThrownBy(() -> template().save(value, model)).isInstanceOf(DuplicateKeyException.class);
        commands.failure = new DataAccessResourceFailureException("connection lost");
        assertThatThrownBy(() -> template().save(value, model)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(value.id).isNull(); assertThat(value.version).isNull();
    }
    @Test void negativeAndOverflowVersionsFailBeforeTransport() {
        for (long version : new long[] {-1, Long.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(() -> template().save(new Versioned("id", version, "text"), model));
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new BoxedInt("id", Integer.MAX_VALUE), new EntityModel<>(BoxedInt.class)));
        assertThat(commands.calls).isZero();
    }
    @Test void metadataRejectsMultipleUnsupportedAndSharedVersionFields() {
        for (Class<?> type : List.of(Multiple.class, Unsupported.class, SharedId.class, SharedAudit.class, FinalMutable.class, StaticVersion.class))
            assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(type));
        assertThatIllegalArgumentException().isThrownBy(() -> template().validateModel(new EntityModel<>(Ignored.class)))
            .withMessageContaining("serialized JSON property");
    }
    @Test void callbacksCannotChangeExpectedVersionOrTheReturnedVersionAfterPersistence() {
        BeforeSaveCallback<Versioned> change = (entity, context) -> new Versioned(entity.id(), 5L, entity.text());
        var template = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), EntityCallbacks.create(change), null);
        assertThatIllegalArgumentException().isThrownBy(() -> template.save(new Versioned("id", 4L, "text"), model)).withMessageContaining("version");
        assertThat(commands.calls).isZero();
        AfterSaveCallback<Versioned> after = (entity, context) -> new Versioned(entity.id(), 100L, entity.text());
        var afterTemplate = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), EntityCallbacks.create(after), null);
        assertThatThrownBy(() -> afterTemplate.save(new Versioned("id", 4L, "text"), model)).isInstanceOf(AfterSaveCallbackException.class);
        assertThat(commands.calls).isEqualTo(1);
    }
    @Test void readCallbacksCannotChangeVersion() {
        commands.reply = "{\"id\":\"id\",\"version\":4,\"text\":\"text\"}";
        AfterConvertCallback<Versioned> change = (entity, key) -> new Versioned(entity.id(), 5L, entity.text());
        var template = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), EntityCallbacks.create(change), null);
        assertThatIllegalArgumentException().isThrownBy(() -> template.findById("id", model)).withMessageContaining("version");
    }
    @Test void persistableMustAgreeWithTheVersionState() {
        var model = new EntityModel<>(ExplicitState.class);
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new ExplicitState("id", null, false), model));
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new ExplicitState("id", 0L, true), model));
        assertThat(commands.calls).isZero();
    }
    @Test void serializedVersionAliasesUseAnExactRootJsonPath() {
        var saved = template().save(new Renamed("id", 8L), new EntityModel<>(Renamed.class));
        assertThat(saved.version()).isEqualTo(9);
        assertThat(commands.args[3]).isEqualTo("$['rev.no']");
        assertThat(commands.args[6].toString()).contains("\"rev.no\":9");
    }
    @Test void deleteUsesTheExpectedVersionWithoutIncrementAndRejectsUnsavedEntities() {
        var value = new Versioned("id", 4L, "text");
        template().delete(value, model);
        assertThat(commands.args[4]).isEqualTo(4L);
        assertThat(commands.args[5]).isEqualTo("DELETE");
        assertThat(value.version()).isEqualTo(4);
        commands.reply = 0L;
        assertThatThrownBy(() -> template().delete(value, model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> template().delete(new Versioned("id", null, ""), model)).isInstanceOf(OptimisticLockingFailureException.class);
    }
    @Test void recordConstructorCannotOverrideTheManagedVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new Altered("id", null), new EntityModel<>(Altered.class)))
            .withMessageContaining("managed version");
        assertThat(commands.calls).isZero();
    }
    private static class RecordingCommands extends DatadamCommands {
        Object reply = 1L; Object[] args; int calls; RuntimeException failure; Runnable beforeReply = () -> {};
        RecordingCommands() { super(null); }
        @Override public Object execute(String command, Object... args) {
            this.args = args; calls++; beforeReply.run(); if (failure != null) throw failure; return reply;
        }
    }
    @DatadamDocument(prefix = "version:") record Versioned(@Id @DatadamGeneratedId String id, @Version Long version, String text) {}
    @DatadamDocument(prefix = "version:") record PrimitiveLong(@Id String id, @Version long version) {}
    @DatadamDocument(prefix = "version:") record PrimitiveInt(@Id String id, @Version int version) {}
    @DatadamDocument(prefix = "version:") record BoxedInt(@Id String id, @Version Integer version) {}
    @DatadamDocument(prefix = "version:") static class Mutable { @Id @DatadamGeneratedId public String id; @Version public Long version; }
    @DatadamDocument(prefix = "bad:") record Multiple(@Id String id, @Version Long one, @Version Long two) {}
    @DatadamDocument(prefix = "bad:") record Unsupported(@Id String id, @Version Double version) {}
    @DatadamDocument(prefix = "bad:") record SharedId(@Id @Version Long id) {}
    @DatadamDocument(prefix = "bad:") record SharedAudit(@Id String id, @Version @LastModifiedDate Long version) {}
    @DatadamDocument(prefix = "bad:") static class FinalMutable { @Id String id; @Version final Long version = null; }
    @DatadamDocument(prefix = "bad:") static class StaticVersion { @Id String id; @Version static Long version; }
    @DatadamDocument(prefix = "bad:") record Ignored(@Id String id, @Version @JsonIgnore Long version) {}
    @DatadamDocument(prefix = "version:") record ExplicitState(@Id String id, @Version Long version, @JsonIgnore boolean fresh) implements Persistable<String> {
        @Override @JsonIgnore public String getId() { return id; }
        @Override @JsonIgnore public boolean isNew() { return fresh; }
    }
    @DatadamDocument(prefix = "version:") record Renamed(@Id String id, @Version @JsonProperty("rev.no") Long version) {}
    @DatadamDocument(prefix = "version:") record Altered(@Id String id, @Version Long version) { Altered { if (version != null) version++; } }
}
