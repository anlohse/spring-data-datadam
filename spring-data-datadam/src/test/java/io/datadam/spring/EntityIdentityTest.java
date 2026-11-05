package io.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.SimpleDatadamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import static org.assertj.core.api.Assertions.*;

class EntityIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturingCommands commands = new CapturingCommands();
    private DatadamTemplate template() { return new DatadamTemplate(commands, mapper); }

    @Test void generatedRecordReturnsACopyWhilePreservingEveryOtherComponent() throws Exception {
        var original = new Generated(null, "text", List.of("one", "two"));
        var model = new EntityModel<>(Generated.class);
        var saved = template().save(original, model);
        assertThat(saved).isNotSameAs(original);
        assertThat(original.id()).isNull();
        assertThat(UUID.fromString(saved.id())).isNotNull();
        assertThat(saved.text()).isEqualTo(original.text());
        assertThat(saved.tags()).isSameAs(original.tags());
        assertThat(commands.args[0]).isEqualTo("generated:" + saved.id());
        assertThat(mapper.readTree(commands.args[2].toString()).get("id").asText()).isEqualTo(saved.id());
        assertThat(commands.args[3]).isEqualTo("NX");
        assertThat(model.isNew(saved)).isFalse();
    }
    @Test void mutableIdentityIsAppliedOnlyAfterTheWriteSucceeds() {
        var original = new Mutable();
        commands.beforeReply = () -> assertThat(original.id).isNull();
        var saved = template().save(original, new EntityModel<>(Mutable.class));
        assertThat(saved).isSameAs(original);
        assertThat(saved.id).isNotBlank();
    }
    @Test void failuresLeaveMutableInputUnchanged() {
        var original = new Mutable();
        commands.failure = new DuplicateKeyException("UNIQUE failure");
        assertThatThrownBy(() -> template().save(original, new EntityModel<>(Mutable.class))).isInstanceOf(DuplicateKeyException.class);
        assertThat(original.id).isNull();
        commands.failure = new DataAccessResourceFailureException("Connection lost");
        assertThatThrownBy(() -> template().save(original, new EntityModel<>(Mutable.class))).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(original.id).isNull();
    }
    @Test void collisionDoesNotAssignTheGeneratedId() {
        commands.reply = null;
        var original = new Mutable();
        assertThatThrownBy(() -> template().save(original, new EntityModel<>(Mutable.class))).isInstanceOf(DuplicateKeyException.class);
        assertThat(original.id).isNull();
    }
    @Test void assignedIdentityNeverInvokesTheGeneratorAndKeepsUpsertBehavior() {
        var template = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", context -> { throw new AssertionError("Must not generate"); }));
        var original = new Generated("provided", "text", List.of());
        assertThat(template.save(original, new EntityModel<>(Generated.class))).isSameAs(original);
        assertThat(commands.args).hasSize(3);
        assertThat(commands.args[0]).isEqualTo("generated:provided");
    }
    @Test void uuidIdRetainsItsJavaTypeAndJacksonPropertyName() throws Exception {
        var saved = template().save(new UuidEntity(null), new EntityModel<>(UuidEntity.class));
        assertThat(saved.id()).isInstanceOf(UUID.class);
        var json = mapper.readTree(commands.args[2].toString());
        assertThat(json.has("id")).isFalse();
        assertThat(json.get("entity_id").asText()).isEqualTo(saved.id().toString());
    }
    @Test void customNamedStrategyGeneratesNumericIdentityWithoutCoercion() {
        var template = new DatadamTemplate(commands, mapper, Map.of("sequence", context -> 42L));
        var saved = template.save(new Numeric(null), new EntityModel<>(Numeric.class));
        assertThat(saved.id()).isEqualTo(42L);
        assertThat(commands.args[0]).isEqualTo("numeric:42");
    }
    @Test void invalidGeneratorValuesFailBeforeTransport() {
        for (Object value : new Object[] {null, "", "  ", 42L}) {
            var template = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", context -> value));
            assertThatIllegalArgumentException().isThrownBy(() -> template.save(new Generated(null, "text", List.of()), new EntityModel<>(Generated.class)));
        }
        assertThat(commands.calls).isZero();
    }
    @Test void invalidMetadataAndMissingStrategiesFailWhenRepositoryIsCreated() {
        for (Class<?> type : List.of(NonIdAnnotation.class, FinalMutable.class, StaticId.class))
            assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(type));
        assertThatIllegalArgumentException().isThrownBy(() -> new SimpleDatadamRepository<>(template(), Numeric.class))
            .withMessageContaining("sequence");
        assertThatIllegalArgumentException().isThrownBy(() -> new SimpleDatadamRepository<>(template(), UnsupportedDefault.class))
            .withMessageContaining("String or UUID");
    }
    @Test void newStateIsLocalAndSupportsPersistableAndPrimitiveZero() {
        var model = new EntityModel<>(Generated.class);
        assertThat(model.isNew(new Generated(null, "", List.of()))).isTrue();
        assertThat(model.isNew(new Generated("  ", "", List.of()))).isTrue();
        assertThat(model.isNew(new Generated("assigned", "", List.of()))).isFalse();
        assertThat(new EntityModel<>(ManualPrimitive.class).isNew(new ManualPrimitive(0))).isTrue();
        assertThat(new EntityModel<>(ManualBoxed.class).isNew(new ManualBoxed(0L))).isFalse();
        assertThat(new EntityModel<>(AssignedNew.class).isNew(new AssignedNew("assigned", true))).isTrue();
        assertThat(new EntityModel<>(AssignedNew.class).isNew(new AssignedNew(null, false))).isFalse();
    }
    @Test void persistableCanRequestInsertionForAssignedIds() {
        var model = new EntityModel<>(AssignedNew.class);
        template().save(new AssignedNew("assigned", true), model);
        assertThat(commands.args[3]).isEqualTo("NX");
        commands.reply = null;
        assertThatThrownBy(() -> template().save(new AssignedNew("assigned", true), model)).isInstanceOf(DuplicateKeyException.class);
    }
    @Test void recordConstructorCannotChangeGeneratedIdentityBehindTheKey() {
        var template = new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", context -> "lowercase"));
        assertThatIllegalArgumentException().isThrownBy(() -> template.save(new Normalizing(null), new EntityModel<>(Normalizing.class)))
            .withMessageContaining("preserve the generated ID");
        assertThat(commands.calls).isZero();
    }
    @Test void ignoredIdentityCannotProduceAnUnrecoverableDocument() {
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new Ignored(null), new EntityModel<>(Ignored.class)))
            .withMessageContaining("serialized JSON property");
        assertThat(commands.calls).isZero();
    }
    @Test void saveAllReturnsTheGeneratedRecordCopies() {
        var repository = new SimpleDatadamRepository<Generated, String>(template(), Generated.class);
        var originals = List.of(new Generated(null, "a", List.of()), new Generated(null, "b", List.of()));
        var saved = repository.saveAll(originals);
        assertThat(saved).extracting(Generated::id).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(originals).extracting(Generated::id).containsOnlyNulls();
    }
    @Test void springConfigurationResolvesNamedStrategiesAndAllowsReplacingTheDefault() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(io.datadam.spring.config.DatadamAutoConfiguration.class))
            .withBean(DatadamCommands.class, () -> commands)
            .withBean("sequence", DatadamIdGenerator.class, () -> context -> 77L)
            .withBean("datadamIdGenerator", DatadamIdGenerator.class, () -> context -> "custom-default")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var template = context.getBean(DatadamTemplate.class);
                assertThat(template.save(new Numeric(null), new EntityModel<>(Numeric.class)).id()).isEqualTo(77L);
                assertThat(template.save(new Generated(null, "", List.of()), new EntityModel<>(Generated.class)).id()).isEqualTo("custom-default");
            });
    }
    @Test void missingManualIdentityIsStillRejectedWithoutOptIn() {
        assertThatIllegalArgumentException().isThrownBy(() -> template().save(new ManualBoxed(null), new EntityModel<>(ManualBoxed.class)));
        assertThat(commands.calls).isZero();
    }
    @Test void primitiveNumericGenerationAndManualZeroHaveDifferentWritePolicies() {
        var numeric = new DatadamTemplate(commands, mapper, Map.of("sequence", context -> 42L));
        var model = new EntityModel<>(PrimitiveGenerated.class);
        assertThat(model.isNew(new PrimitiveGenerated(0))).isTrue();
        assertThat(numeric.save(new PrimitiveGenerated(0), model).id()).isEqualTo(42);
        assertThat(commands.args[3]).isEqualTo("NX");
        template().save(new ManualPrimitive(0), new EntityModel<>(ManualPrimitive.class));
        assertThat(commands.args).hasSize(3);
        assertThat(commands.args[0]).isEqualTo("manual:0");
    }

    private static class CapturingCommands extends DatadamCommands {
        Object[] args;
        Object reply = "OK";
        int calls;
        RuntimeException failure;
        Runnable beforeReply = () -> {};
        CapturingCommands() { super(null); }
        @Override public Object execute(String command, Object... args) {
            this.args = args; calls++; beforeReply.run();
            if (failure != null) throw failure;
            return reply;
        }
    }
    @DatadamDocument(prefix = "generated:") record Generated(@Id @DatadamGeneratedId String id, String text, List<String> tags) {}
    @DatadamDocument(prefix = "mutable:") static class Mutable { @Id @DatadamGeneratedId public String id; public String text = "mutable"; }
    @DatadamDocument(prefix = "uuid:") record UuidEntity(@Id @DatadamGeneratedId @JsonProperty("entity_id") UUID id) {}
    @DatadamDocument(prefix = "numeric:") record Numeric(@Id @DatadamGeneratedId(generator = "sequence") Long id) {}
    @DatadamDocument(prefix = "primitive:") record PrimitiveGenerated(@Id @DatadamGeneratedId(generator = "sequence") long id) {}
    @DatadamDocument(prefix = "invalid:") record NonIdAnnotation(@Id String id, @DatadamGeneratedId String other) {}
    @DatadamDocument(prefix = "final:") static class FinalMutable { @Id @DatadamGeneratedId final String id = null; }
    @DatadamDocument(prefix = "static:") static class StaticId { @Id static String id; }
    @DatadamDocument(prefix = "unsupported:") record UnsupportedDefault(@Id @DatadamGeneratedId Long id) {}
    @DatadamDocument(prefix = "manual:") record ManualPrimitive(@Id long id) {}
    @DatadamDocument(prefix = "manual:") record ManualBoxed(@Id Long id) {}
    @DatadamDocument(prefix = "assigned:") record AssignedNew(@Id String id, @JsonIgnore boolean fresh) implements Persistable<String> {
        @Override @JsonIgnore public String getId() { return id; }
        @Override @JsonIgnore public boolean isNew() { return fresh; }
    }
    @DatadamDocument(prefix = "normalizing:") record Normalizing(@Id @DatadamGeneratedId String id) {
        Normalizing { if (id != null) id = id.toUpperCase(Locale.ROOT); }
    }
    @DatadamDocument(prefix = "ignored:") record Ignored(@Id @DatadamGeneratedId @JsonIgnore String id) {}
}
