package io.datadam.demo;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.callback.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuditingIntegrationIT.Fixtures.class)
class AuditingIntegrationIT {
    @Autowired AuditedNoteRepository repository;
    @Autowired DatadamTemplate template;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired TestClock clock;
    @Autowired Actor actor;
    @Autowired Probe probe;
    private String category;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(AuditedNote.class, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() {
        category = "audit-it-" + UUID.randomUUID(); clock.now.set(Instant.parse("2026-11-05T12:00:00Z")); actor.name.set("alice"); probe.reset();
    }
    @AfterEach void cleanup() {
        probe.reset(); repository.findByCategory(category).forEach(repository::delete); probe.reset();
    }
    private AuditedNote note() { return new AuditedNote(null, UUID.randomUUID().toString(), category, "   original text   ", null, null, null, null); }
    private long deadline(String id) { return ((Number) template.commands().execute("PEXPIRETIME", "demo:audited:" + id)).longValue(); }

    @Test void createsAuditedRecordAndExecutesNormalizationAndValidationBeforePersistence() {
        var input = note();
        var saved = repository.save(input);
        assertThat(saved.id()).isNotBlank();
        assertThat(input.createdAt()).isNull();
        assertThat(saved.text()).isEqualTo("original text");
        assertThat(saved.createdAt()).isEqualTo(clock.instant());
        assertThat(saved.modifiedAt()).isEqualTo(clock.instant());
        assertThat(saved.createdBy()).isEqualTo("alice");
        assertThat(saved.modifiedBy()).isEqualTo("alice");
        assertThat(probe.events).containsExactly("before-convert", "before-save", "after-save");
        assertThat(probe.auditedBeforeWrite.createdAt()).isEqualTo(clock.instant());
        assertThat(probe.savedKey).isEqualTo("demo:audited:" + saved.id());
        assertThat(repository.findById(saved.id())).contains(saved);
        assertThat(deadline(saved.id())).isPositive();
    }
    @Test void updateChangesOnlyModificationMetadataAndKeepsCreationAndIdentity() {
        var first = repository.save(note());
        clock.now.set(clock.instant().plusSeconds(3600)); actor.name.set("bob");
        var saved = repository.save(first.withContent(first.reference(), category, "updated"));
        assertThat(saved.id()).isEqualTo(first.id());
        assertThat(saved.createdAt()).isEqualTo(first.createdAt());
        assertThat(saved.createdBy()).isEqualTo("alice");
        assertThat(saved.modifiedAt()).isEqualTo(clock.instant());
        assertThat(saved.modifiedBy()).isEqualTo("bob");
        assertThat(repository.lookup("demo:audited:reference", saved.reference())).containsExactly(saved);
    }
    @Test void afterConvertRunsForEveryEntityReadRouteAndNotForNativeCounts() {
        var saved = repository.save(note());
        String key = "demo:audited:" + saved.id();
        probe.reset();
        assertThat(repository.findById(saved.id())).contains(saved);
        assertThat(repository.lookup("demo:audited:reference", saved.reference())).containsExactly(saved);
        assertThat(repository.findByCategory(category)).containsExactly(saved);
        assertThat(repository.search("demo:audited:search", new SearchQuery("@category:{$c}", Map.of("c", category), Sort.unsorted()), PageRequest.of(0, 10)).getContent()).containsExactly(saved);
        assertThat(repository.findAllById(List.of(saved.id()))).containsExactly(saved);
        assertThat(probe.readKeys).containsExactly(key, key, key, key, key);
        probe.reset();
        assertThat(repository.findAll()).contains(saved);
        assertThat(probe.readKeys).contains(key);
        probe.reset();
        assertThat(repository.count("demo:audited:category", CompoundQuery.all().where("category", "=", category))).isEqualTo(1);
        assertThat(probe.events).isEmpty();
    }
    @Test void beforeSaveRefusalLeavesStoredDocumentAndExactExpiryUnchanged() {
        var original = repository.save(note());
        long before = deadline(original.id());
        clock.now.set(clock.instant().plusSeconds(60)); actor.name.set("bob");
        probe.reset(); probe.rejectBefore = true;
        assertThatIllegalArgumentException().isThrownBy(() -> repository.save(original.withContent(original.reference(), category, "refused")))
            .withMessageContaining("rejected before writing");
        assertThat(probe.events).doesNotContain("after-save");
        probe.rejectBefore = false;
        assertThat(repository.findById(original.id())).contains(original);
        assertThat(deadline(original.id())).isEqualTo(before);
    }
    @Test void uniqueRefusalNeverFiresAfterSaveOrRewritesTheOwnerAuditData() {
        var owner = repository.save(note());
        long before = deadline(owner.id());
        clock.now.set(clock.instant().plusSeconds(60)); actor.name.set("bob"); probe.reset();
        var input = new AuditedNote(null, owner.reference(), category, "conflict", null, null, null, null);
        assertThatThrownBy(() -> repository.save(input)).isInstanceOf(DuplicateKeyException.class);
        assertThat(input.id()).isNull();
        assertThat(input.createdAt()).isNull();
        assertThat(probe.events).doesNotContain("after-save");
        assertThat(repository.findByCategory(category)).containsExactly(owner);
        assertThat(deadline(owner.id())).isEqualTo(before);
    }
    @Test void afterSaveRefusalIsExplicitlyReportedAsAnAcknowledgedWrite() {
        probe.rejectAfter = true;
        assertThatThrownBy(() -> repository.save(note())).isInstanceOfSatisfying(AfterSaveCallbackException.class,
            error -> assertThat(error.getKey()).isEqualTo(probe.savedKey));
        probe.rejectAfter = false;
        var stored = repository.findByCategory(category);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).createdBy()).isEqualTo("alice");
    }
    @Test void mutableAuditFieldsRoundTripAndUnrelatedTypedCallbacksAreSkipped() {
        var model = new EntityModel<>(MutableAuditedNote.class);
        var input = new MutableAuditedNote();
        input.reference = UUID.randomUUID().toString(); input.category = category; input.text = "mutable";
        var saved = template.save(input, model);
        assertThat(saved).isSameAs(input);
        assertThat(saved.createdBy).isEqualTo("alice");
        assertThat(saved.createdAt).isEqualTo(clock.instant());
        assertThat(probe.events).isEmpty();
        var loaded = template.findById(saved.id, model).orElseThrow();
        clock.now.set(clock.instant().plusSeconds(1)); actor.name.set("bob");
        template.save(loaded, model);
        assertThat(loaded.createdBy).isEqualTo("alice");
        assertThat(loaded.modifiedBy).isEqualTo("bob");
    }
    @Test void annotationMetadataAloneDoesNotForceAuditingInAStandaloneTemplate() {
        var plain = new DatadamTemplate(template.commands(), mapper);
        var saved = plain.save(note(), new EntityModel<>(AuditedNote.class));
        assertThat(saved.createdAt()).isNull();
        assertThat(saved.createdBy()).isNull();
        assertThat(probe.events).isEmpty();
        assertThat(repository.findById(saved.id())).contains(saved);
    }
    @Test void httpInputCannotSupplyAuditFieldsAndUpdatesPreserveCreationMetadata() {
        var input = note();
        var request = Map.of("reference", input.reference(), "category", category, "text", input.text(), "createdBy", "forged", "createdAt", "1900-01-01T00:00:00Z");
        var created = http.postForEntity("/audited-notes", request, AuditedNote.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        var first = created.getBody();
        assertThat(first.createdBy()).isEqualTo("alice");
        assertThat(first.createdAt()).isEqualTo(clock.instant());
        assertThat(first.text()).isEqualTo("original text");
        clock.now.set(clock.instant().plusSeconds(1)); actor.name.set("bob");
        var update = new AuditedNoteController.NoteInput(first.reference(), category, "updated");
        var result = http.exchange("/audited-notes/" + first.id(), HttpMethod.PUT, new HttpEntity<>(update), AuditedNote.class).getBody();
        assertThat(result.createdAt()).isEqualTo(first.createdAt());
        assertThat(result.modifiedBy()).isEqualTo("bob");
        var invalid = new AuditedNoteController.NoteInput(UUID.randomUUID().toString(), category, "   ");
        assertThat(http.postForEntity("/audited-notes", invalid, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean @Primary Actor actor() { return new Actor(); }
        @Bean Probe probe() { return new Probe(); }
    }
    static class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return now.get(); }
    }
    static class Actor implements AuditorAware<String> {
        final AtomicReference<String> name = new AtomicReference<>();
        public Optional<String> getCurrentAuditor() { return Optional.ofNullable(name.get()); }
    }
    static class Probe implements BeforeConvertCallback<AuditedNote>, BeforeSaveCallback<AuditedNote>, AfterSaveCallback<AuditedNote>, AfterConvertCallback<AuditedNote>, Ordered {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> readKeys = new CopyOnWriteArrayList<>();
        volatile boolean rejectBefore, rejectAfter;
        volatile String savedKey;
        volatile AuditedNote auditedBeforeWrite;
        void reset() { events.clear(); readKeys.clear(); rejectBefore = false; rejectAfter = false; savedKey = null; auditedBeforeWrite = null; }
        public int getOrder() { return 100; }
        public AuditedNote onBeforeConvert(AuditedNote entity, DatadamSaveContext context) { events.add("before-convert"); return entity; }
        public AuditedNote onBeforeSave(AuditedNote entity, DatadamSaveContext context) {
            events.add("before-save"); auditedBeforeWrite = entity;
            if (rejectBefore) throw new IllegalArgumentException("rejected before writing"); return entity;
        }
        public AuditedNote onAfterSave(AuditedNote entity, DatadamSaveContext context) {
            events.add("after-save"); savedKey = context.key();
            if (rejectAfter) throw new IllegalStateException("rejected after writing"); return entity;
        }
        public AuditedNote onAfterConvert(AuditedNote entity, String key) { events.add("after-convert"); readKeys.add(key); return entity; }
    }
    @DatadamDocument(prefix = "demo:audited:", timeToLive = 300)
    public static class MutableAuditedNote {
        @Id @DatadamGeneratedId public String id;
        public String reference, category, text;
        @CreatedDate public Instant createdAt;
        @LastModifiedDate public Instant modifiedAt;
        @CreatedBy public String createdBy;
        @LastModifiedBy public String modifiedBy;
    }
}
