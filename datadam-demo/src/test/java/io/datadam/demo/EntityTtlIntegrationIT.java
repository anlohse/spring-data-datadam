package io.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.datadam.spring.*;
import io.datadam.spring.annotation.DatadamDocument;
import io.datadam.spring.annotation.DatadamTimeToLive;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntityTtlIntegrationIT {
    @Autowired TemporaryNoteRepository repository;
    @Autowired DatadamTemplate template;
    @Autowired TestRestTemplate http;
    private final List<String> ids = new ArrayList<>();
    private String category;

    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(TemporaryNote.class, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { category = "ttl-it-" + UUID.randomUUID(); }
    @AfterEach void cleanup() { ids.forEach(repository::deleteById); }
    private TemporaryNote note(Long ttl) {
        String id = "ttl-it-" + UUID.randomUUID();
        ids.add(id);
        return new TemporaryNote(id, id + "-ref", category, "temporary note", ttl);
    }
    private long pttl(String id) { return ((Number) template.commands().execute("PTTL", "demo:temporary:" + id)).longValue(); }
    private long deadline(String id) { return ((Number) template.commands().execute("PEXPIRETIME", "demo:temporary:" + id)).longValue(); }
    private long count() { return repository.count("demo:temporary:category", CompoundQuery.all().where("category", "=", category)); }
    private long searchCount() {
        return repository.search("demo:temporary:search", new SearchQuery("@category:{$c}", Map.of("c", category), org.springframework.data.domain.Sort.unsorted()),
            PageRequest.of(0, 10)).getTotalElements();
    }
    @Test void typeDefaultIsAppliedAndPolicyIsNotAReadCountdown() {
        var note = note(null);
        repository.save(note);
        assertThat(pttl(note.id())).isBetween(50_000L, 60_000L);
        assertThat(repository.findById(note.id())).contains(note);
        assertThat(repository.findByCategory(category)).containsExactly(note);
        assertThat(searchCount()).isEqualTo(1);
    }
    @Test void durationPolicyUsesItsOwnUnitAndRoundTripsThroughJackson() {
        var base = note(null);
        var value = new DurationNote(base.id(), base.reference(), category, "duration policy", Duration.ofSeconds(90));
        var model = new EntityModel<>(DurationNote.class);
        template.save(value, model);
        assertThat(pttl(base.id())).isBetween(80_000L, 90_000L);
        assertThat(template.findById(base.id(), model)).contains(value);
    }
    @Test void perEntityMillisecondsOverrideAndExpirationRemoveBothIndexFamilies() {
        var note = repository.save(note(1500L));
        assertThat(pttl(note.id())).isBetween(1L, 1500L);
        assertThat(count()).isEqualTo(1);
        assertThat(searchCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(repository.findById(note.id())).isEmpty();
            assertThat(count()).isZero();
            assertThat(searchCount()).isZero();
            assertThat(repository.lookup("demo:temporary:reference", note.reference())).isEmpty();
        });
    }
    @Test void successfulUpdatesRefreshAndChangeTtlAndNullReturnsToTypeDefault() {
        var note = repository.save(note(600_000L));
        long first = deadline(note.id());
        await().pollDelay(Duration.ofMillis(30)).atMost(Duration.ofSeconds(2)).until(() -> System.currentTimeMillis() > first - 600_000 + 20);
        repository.save(note);
        assertThat(deadline(note.id())).isGreaterThan(first);
        repository.save(new TemporaryNote(note.id(), note.reference(), category, "shorter", 120_000L));
        assertThat(pttl(note.id())).isBetween(110_000L, 120_000L);
        repository.save(new TemporaryNote(note.id(), note.reference(), category, "default", null));
        assertThat(pttl(note.id())).isBetween(50_000L, 60_000L);
    }
    @Test void absentPolicyPreservesExistingAbsoluteDeadlineAndNewKeysRemainPersistent() {
        var existing = repository.save(note(120_000L));
        long before = deadline(existing.id());
        var model = new EntityModel<>(PreservingNote.class);
        template.save(new PreservingNote(existing.id(), existing.reference(), category, "updated without TTL policy"), model);
        assertThat(deadline(existing.id())).isEqualTo(before);
        var fresh = note(null);
        template.save(new PreservingNote(fresh.id(), fresh.reference(), category, fresh.text()), model);
        assertThat(pttl(fresh.id())).isEqualTo(-1);
    }
    @Test void persistOnlyRemovesExpiryAndTheNextConfiguredSaveAppliesItAgain() {
        var note = repository.save(note(null));
        assertThat(repository.persist(note.id())).isTrue();
        assertThat(pttl(note.id())).isEqualTo(-1);
        assertThat(repository.findById(note.id())).contains(note);
        assertThat(count()).isEqualTo(1);
        assertThat(searchCount()).isEqualTo(1);
        assertThat(repository.persist(note.id())).isFalse();
        assertThat(repository.persist("absent-" + note.id())).isFalse();
        repository.save(note);
        assertThat(pttl(note.id())).isBetween(50_000L, 60_000L);
    }
    @Test void uniqueRefusalPreservesDocumentAndExactDeadlineForUpdateAndInsert() {
        var owner = repository.save(note(120_000L));
        var original = repository.save(note(60_000L));
        long before = deadline(original.id());
        long ownerBefore = deadline(owner.id());
        var conflict = new TemporaryNote(original.id(), owner.reference(), category, "must not replace", 300_000L);
        assertThatThrownBy(() -> repository.save(conflict)).isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.findById(original.id())).contains(original);
        assertThat(deadline(original.id())).isEqualTo(before);
        assertThat(deadline(owner.id())).isEqualTo(ownerBefore);
        var fresh = note(300_000L);
        assertThatThrownBy(() -> repository.save(new TemporaryNote(fresh.id(), owner.reference(), category, "must not create", fresh.ttlMillis())))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(pttl(fresh.id())).isEqualTo(-2);
        assertThat(count()).isEqualTo(2);
        assertThat(searchCount()).isEqualTo(2);
    }
    @Test void invalidValuesCannotChangeTheDocumentOrItsExpiry() {
        var original = repository.save(note(null));
        long before = deadline(original.id());
        for (long value : new long[] {0, -1, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> repository.save(
                new TemporaryNote(original.id(), original.reference(), category, "invalid", value)));
            assertThat(repository.findById(original.id())).contains(original);
            assertThat(deadline(original.id())).isEqualTo(before);
        }
    }
    @Test void deniedExpirationFailsBeforeTheJsonWriteAndCannotChangeExistingDeadline() {
        var original = repository.save(note(null));
        long before = deadline(original.id());
        var fresh = note(null);
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), new com.fasterxml.jackson.databind.ObjectMapper());
            var model = new EntityModel<>(TemporaryNote.class);
            assertThatThrownBy(() -> restricted.save(fresh, model)).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> restricted.save(new TemporaryNote(original.id(), original.reference(), category, "denied", 120_000L), model))
                .isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> restricted.persist(original.id(), model)).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
        assertThat(repository.findById(fresh.id())).isEmpty();
        assertThat(repository.findById(original.id())).contains(original);
        assertThat(deadline(original.id())).isEqualTo(before);
    }
    @Test void anotherConnectionCannotObserveNewDocumentWithoutItsTtl() throws Exception {
        List<TemporaryNote> notes = new ArrayList<>();
        for (int i = 0; i < 30; i++) notes.add(note(60_000L));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> notes.forEach(repository::save));
            for (var note : notes) {
                await().atMost(Duration.ofSeconds(10)).until(() -> repository.findById(note.id()).isPresent());
                assertThat(pttl(note.id())).isPositive();
            }
            writer.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
    @Test void httpExampleExposesDefaultOverrideAndExplicitRemoval() {
        var note = note(30_000L);
        assertThat(http.postForEntity("/temporary-notes", note, TemporaryNote.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/temporary-notes/" + note.id(), TemporaryNote.class)).isEqualTo(note);
        var remaining = http.getForObject("/temporary-notes/" + note.id() + "/ttl", Map.class);
        assertThat(((Number) remaining.get("remainingMilliseconds")).longValue()).isBetween(1L, 30_000L);
        assertThat(http.postForEntity("/temporary-notes/" + note.id() + "/persist", null, Map.class).getBody()).containsEntry("expirationRemoved", true);
        assertThat(pttl(note.id())).isEqualTo(-1);
        var invalid = new TemporaryNote(note.id(), note.reference(), category, "invalid", 0L);
        assertThat(http.postForEntity("/temporary-notes", invalid, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @DatadamDocument(prefix = "demo:temporary:")
    record PreservingNote(@Id String id, String reference, String category, String text) {}
    @DatadamDocument(prefix = "demo:temporary:")
    record DurationNote(@Id String id, String reference, String category, String text, @DatadamTimeToLive Duration lifetime) {}
}
