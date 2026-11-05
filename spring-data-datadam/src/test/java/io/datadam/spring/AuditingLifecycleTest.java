package io.datadam.spring;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.annotation.*;
import io.datadam.spring.auditing.DatadamAuditing;
import io.datadam.spring.callback.*;
import io.datadam.spring.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.data.annotation.*;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mapping.callback.*;
import static org.assertj.core.api.Assertions.*;

class AuditingLifecycleTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T12:00:00Z"));
    private final AtomicReference<String> actor = new AtomicReference<>("alice");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MemoryCommands commands = new MemoryCommands();
    private final EntityModel<Audited> model = new EntityModel<>(Audited.class);
    private DatadamAuditing auditing() { return new DatadamAuditing(() -> Optional.of(now.get()), () -> Optional.ofNullable(actor.get()), true, true); }
    private DatadamTemplate template(EntityCallbacks callbacks, DatadamAuditing auditing) {
        return new DatadamTemplate(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), callbacks, auditing);
    }
    private Audited fresh() { return new Audited(null, "draft", null, null, null, null); }

    @Test void recordAuditUsesCapturedNewStateBeforeIdGenerationAndPreservesCreationOnUpdate() {
        var template = template(EntityCallbacks.create(), auditing());
        var original = fresh();
        var first = template.save(original, model);
        assertThat(first.createdAt()).isEqualTo(now.get());
        assertThat(first.modifiedAt()).isEqualTo(now.get());
        assertThat(first.createdBy()).isEqualTo("alice");
        assertThat(first.modifiedBy()).isEqualTo("alice");
        assertThat(original.id()).isNull();
        assertThat(original.createdAt()).isNull();
        actor.set("bob"); now.set(now.get().plusSeconds(60));
        var second = template.save(first, model);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.createdBy()).isEqualTo("alice");
        assertThat(second.modifiedAt()).isEqualTo(now.get());
        assertThat(second.modifiedBy()).isEqualTo("bob");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(template.findById(second.id(), model)).contains(second);
    }
    @Test void mutableAuditingAndInheritedFieldsWorkWithControlledClock() {
        var value = new MutableAudit();
        var saved = template(EntityCallbacks.create(), auditing()).save(value, new EntityModel<>(MutableAudit.class));
        assertThat(saved).isSameAs(value);
        assertThat(saved.id).isNotBlank();
        assertThat(saved.created).isEqualTo(now.get());
        assertThat(saved.modified).isEqualTo(now.get());
    }
    @Test void auditingIsOptionalAndDoesNotTouchAnnotatedFieldsWhenDisabled() {
        var saved = template(EntityCallbacks.create(), null).save(fresh(), model);
        assertThat(saved.createdAt()).isNull();
        assertThat(saved.modifiedBy()).isNull();
    }
    @Test void datesDoNotRequireAnAuditorAndEmptyAuditorPreservesPreviousValues() {
        var datesOnly = new DatadamAuditing(() -> Optional.of(now.get()), null, true, true);
        var saved = template(EntityCallbacks.create(), datesOnly).save(fresh(), model);
        assertThat(saved.createdAt()).isEqualTo(now.get());
        assertThat(saved.createdBy()).isNull();
        var full = template(EntityCallbacks.create(), auditing()).save(fresh(), model);
        actor.set(null); now.set(now.get().plusSeconds(1));
        var updated = template(EntityCallbacks.create(), auditing()).save(full, model);
        assertThat(updated.modifiedBy()).isEqualTo("alice");
    }
    @Test void modifyOnCreationAndDatesCanBeConfiguredIndependently() {
        var creationOnly = new DatadamAuditing(() -> Optional.of(now.get()), () -> Optional.of("alice"), false, true);
        var saved = template(EntityCallbacks.create(), creationOnly).save(fresh(), model);
        assertThat(saved.createdAt()).isEqualTo(now.get());
        assertThat(saved.modifiedAt()).isNull();
        assertThat(saved.modifiedBy()).isNull();
        var authorsOnly = new DatadamAuditing(() -> Optional.of(now.get()), () -> Optional.of("alice"), true, false);
        var author = template(EntityCallbacks.create(), authorsOnly).save(fresh(), model);
        assertThat(author.createdAt()).isNull();
        assertThat(author.createdBy()).isEqualTo("alice");
    }
    @Test void lifecycleOrderIsBeforeConvertAuditBeforeSaveWriteAfterSaveAndAfterConvert() {
        var events = new ArrayList<String>();
        var callbacks = EntityCallbacks.create(new Lifecycle(events));
        commands.onWrite = () -> events.add("write");
        var template = template(callbacks, auditing());
        var saved = template.save(fresh(), model);
        assertThat(events).containsExactly("before-convert", "before-save", "write", "after-save");
        assertThat(saved.text()).isEqualTo("normalized");
        template.findById(saved.id(), model);
        assertThat(events).endsWith("after-convert");
    }
    @Test void callbackOrderingAndEntityTypeFilteringUseSpringInfrastructure() {
        var events = new ArrayList<Integer>();
        var callbacks = EntityCallbacks.create(new OrderedSave(20, events), new OrderedSave(10, events), new OtherType());
        template(callbacks, null).save(fresh(), model);
        assertThat(events).containsExactly(10, 20);
    }
    @Test void refusalBeforeWritingStopsPersistenceAndAfterSave() {
        var callbacks = EntityCallbacks.create((BeforeSaveCallback<Audited>) (entity, context) -> { throw new IllegalArgumentException("rejected"); },
            (AfterSaveCallback<Audited>) (entity, context) -> { throw new AssertionError("Must not run"); });
        assertThatIllegalArgumentException().isThrownBy(() -> template(callbacks, auditing()).save(fresh(), model)).withMessageContaining("rejected");
        assertThat(commands.writes).isZero();
    }
    @Test void identityChangesAndCreationChangesAreRefusedBeforeWriting() {
        BeforeConvertCallback<Audited> changeId = (entity, context) -> new Audited("changed", entity.text(), entity.createdAt(), entity.modifiedAt(), entity.createdBy(), entity.modifiedBy());
        assertThatIllegalArgumentException().isThrownBy(() -> template(EntityCallbacks.create(changeId), null).save(fresh(), model)).withMessageContaining("identity");
        var saved = template(EntityCallbacks.create(), auditing()).save(fresh(), model);
        BeforeConvertCallback<Audited> changeCreated = (entity, context) -> new Audited(entity.id(), entity.text(), Instant.EPOCH, entity.modifiedAt(), entity.createdBy(), entity.modifiedBy());
        assertThatIllegalArgumentException().isThrownBy(() -> template(EntityCallbacks.create(changeCreated), auditing()).save(saved, model))
            .withMessageContaining("protected audit property");
        assertThat(commands.writes).isEqualTo(1);
    }
    @Test void beforeSaveCannotOverrideAuditValuesAndNullCallbackIsRejected() {
        BeforeSaveCallback<Audited> changeAudit = (entity, context) -> new Audited(entity.id(), entity.text(), entity.createdAt(), Instant.EPOCH, entity.createdBy(), entity.modifiedBy());
        assertThatIllegalArgumentException().isThrownBy(() -> template(EntityCallbacks.create(changeAudit), auditing()).save(fresh(), model))
            .withMessageContaining("protected audit property");
        assertThatThrownBy(() -> template(EntityCallbacks.create((BeforeSaveCallback<Audited>) (e, c) -> null), null).save(fresh(), model))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands.writes).isZero();
    }
    @Test void afterSaveFailureReportsAcknowledgedPersistenceAndTheStoredKey() {
        var callback = (AfterSaveCallback<Audited>) (entity, context) -> { throw new IllegalStateException("post-write failure"); };
        assertThatThrownBy(() -> template(EntityCallbacks.create(callback), auditing()).save(fresh(), model))
            .isInstanceOfSatisfying(AfterSaveCallbackException.class, e -> assertThat(e.getKey()).startsWith("audit:"));
        assertThat(commands.writes).isEqualTo(1);
        assertThat(commands.stored).isNotNull();
    }
    @Test void afterConvertCanEnrichValuesWithoutRewritingAndCannotChangeIdentity() {
        var base = template(EntityCallbacks.create(), auditing());
        var saved = base.save(fresh(), model);
        var enrich = (AfterConvertCallback<Audited>) (entity, key) -> new Audited(entity.id(), "enriched", entity.createdAt(), entity.modifiedAt(), entity.createdBy(), entity.modifiedBy());
        assertThat(template(EntityCallbacks.create(enrich), auditing()).findById(saved.id(), model).orElseThrow().text()).isEqualTo("enriched");
        assertThat(base.findById(saved.id(), model).orElseThrow().text()).isEqualTo("draft");
        var changeId = (AfterConvertCallback<Audited>) (entity, key) -> new Audited("other", entity.text(), entity.createdAt(), entity.modifiedAt(), entity.createdBy(), entity.modifiedBy());
        assertThatIllegalArgumentException().isThrownBy(() -> template(EntityCallbacks.create(changeId), auditing()).findById(saved.id(), model));
        assertThat(commands.writes).isEqualTo(1);
    }
    @Test void invalidAuditRolesFailAtRepositoryValidation() {
        assertThatIllegalArgumentException().isThrownBy(() -> template(EntityCallbacks.create(), auditing()).validateModel(new EntityModel<>(BadAudit.class)))
            .withMessageContaining("one role");
    }
    @Test void springOptInUsesDateTimeProviderAndAuditorAwareReferences() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DatadamAutoConfiguration.class))
            .withUserConfiguration(AuditConfiguration.class).withBean(DatadamCommands.class, () -> commands).run(context -> {
                assertThat(context).hasNotFailed();
                var saved = context.getBean(DatadamTemplate.class).save(fresh(), model);
                assertThat(saved.createdBy()).isEqualTo("configured");
                assertThat(saved.createdAt()).isEqualTo(Instant.EPOCH);
            });
    }
    @Test void unsupportedNestedAndIgnoredAuditPropertiesAreRejectedBeforeWriting() {
        var template = template(EntityCallbacks.create(), auditing());
        assertThatIllegalArgumentException().isThrownBy(() -> template.validateModel(new EntityModel<>(NestedAudit.class))).withMessageContaining("root only");
        assertThatIllegalArgumentException().isThrownBy(() -> template.validateModel(new EntityModel<>(IgnoredAudit.class))).withMessageContaining("serialized");
        assertThat(commands.writes).isZero();
    }
    @Test void absentTimeLeavesDatesUntouchedAndUnsupportedDateTypeCannotWrite() {
        var withoutTime = new DatadamAuditing(Optional::empty, () -> Optional.of("alice"), true, true);
        var saved = template(EntityCallbacks.create(), withoutTime).save(fresh(), model);
        assertThat(saved.createdAt()).isNull();
        assertThat(saved.createdBy()).isEqualTo("alice");
        int before = commands.writes;
        assertThatThrownBy(() -> template(EntityCallbacks.create(), auditing()).save(new BadDate(null, null), new EntityModel<>(BadDate.class)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands.writes).isEqualTo(before);
    }
    @Test void springDateConversionsSupportEpochMillisecondsAndLegacyDate() {
        var value = new LegacyDates(null, null, null);
        var saved = template(EntityCallbacks.create(), auditing()).save(value, new EntityModel<>(LegacyDates.class));
        assertThat(saved.createdAt()).isEqualTo(now.get().toEpochMilli());
        assertThat(saved.modifiedAt()).isEqualTo(Date.from(now.get()));
    }
    @Test void assignedIdsUsePersistableToDeclareCreationWithoutDatabaseProbing() {
        var entity = new AssignedAudit("provided", true, null);
        var saved = template(EntityCallbacks.create(), auditing()).save(entity, new EntityModel<>(AssignedAudit.class));
        assertThat(saved.createdAt()).isEqualTo(now.get());
        assertThat(saved.id()).isEqualTo("provided");
        assertThat(commands.writes).isEqualTo(1);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableDatadamAuditing(auditorAwareRef = "actor", dateTimeProviderRef = "time")
    static class AuditConfiguration {
        @Bean AuditorAware<String> actor() { return () -> Optional.of("configured"); }
        @Bean DateTimeProvider time() { return () -> Optional.of(Instant.EPOCH); }
    }
    @DatadamDocument(prefix = "audit:")
    record Audited(@Id @DatadamGeneratedId String id, String text, @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt,
        @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {}
    static class BaseAudit { @CreatedDate public Instant created; @LastModifiedDate public Instant modified; }
    @DatadamDocument(prefix = "mutable-audit:") static class MutableAudit extends BaseAudit { @Id @DatadamGeneratedId public String id; }
    @DatadamDocument(prefix = "bad-audit:") record BadAudit(@Id String id, @CreatedDate @LastModifiedDate Instant date) {}
    record AuditData(@CreatedDate Instant createdAt) {}
    @DatadamDocument(prefix = "nested:") record NestedAudit(@Id String id, AuditData audit) {}
    @DatadamDocument(prefix = "ignored-audit:") record IgnoredAudit(@Id String id, @CreatedDate @com.fasterxml.jackson.annotation.JsonIgnore Instant createdAt) {}
    @DatadamDocument(prefix = "bad-date:") record BadDate(@Id @DatadamGeneratedId String id, @CreatedDate Boolean createdAt) {}
    @DatadamDocument(prefix = "legacy:") record LegacyDates(@Id @DatadamGeneratedId String id, @CreatedDate Long createdAt, @LastModifiedDate Date modifiedAt) {}
    @DatadamDocument(prefix = "assigned-audit:") record AssignedAudit(@Id String id, @com.fasterxml.jackson.annotation.JsonIgnore boolean fresh,
        @CreatedDate Instant createdAt) implements org.springframework.data.domain.Persistable<String> {
        @Override @com.fasterxml.jackson.annotation.JsonIgnore public String getId() { return id; }
        @Override @com.fasterxml.jackson.annotation.JsonIgnore public boolean isNew() { return fresh; }
    }
    static class Lifecycle implements BeforeConvertCallback<Audited>, BeforeSaveCallback<Audited>, AfterSaveCallback<Audited>, AfterConvertCallback<Audited> {
        private final List<String> events;
        Lifecycle(List<String> events) { this.events = events; }
        public Audited onBeforeConvert(Audited e, DatadamSaveContext c) { events.add("before-convert"); assertThat(e.createdAt()).isNull(); return new Audited(e.id(), "normalized", e.createdAt(), e.modifiedAt(), e.createdBy(), e.modifiedBy()); }
        public Audited onBeforeSave(Audited e, DatadamSaveContext c) { events.add("before-save"); assertThat(e.createdAt()).isNotNull(); assertThat(c.newEntity()).isTrue(); assertThat(c.key()).isNull(); return e; }
        public Audited onAfterSave(Audited e, DatadamSaveContext c) { events.add("after-save"); assertThat(e.id()).isNotNull(); assertThat(c.key()).isEqualTo("audit:" + e.id()); return e; }
        public Audited onAfterConvert(Audited e, String key) { events.add("after-convert"); return e; }
    }
    static class OrderedSave implements BeforeSaveCallback<Audited>, Ordered {
        final int order; final List<Integer> events;
        OrderedSave(int order, List<Integer> events) { this.order = order; this.events = events; }
        public int getOrder() { return order; }
        public Audited onBeforeSave(Audited e, DatadamSaveContext c) { events.add(order); return e; }
    }
    static class OtherType implements BeforeSaveCallback<String> { public String onBeforeSave(String e, DatadamSaveContext c) { throw new AssertionError("Wrong type"); } }
    private static class MemoryCommands extends DatadamCommands {
        String stored; int writes; Runnable onWrite = () -> {};
        MemoryCommands() { super(null); }
        @Override public Object execute(String command, Object... args) {
            if (command.equals("JSON.GET")) return stored;
            if (command.equals("JSON.SET")) { writes++; stored = args[2].toString(); onWrite.run(); return "OK"; }
            throw new AssertionError("Unexpected command " + command);
        }
    }
}
