package com.anlohse.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anlohse.datadam.spring.callback.AfterConvertCallback;
import com.anlohse.datadam.spring.repository.SimpleDatadamRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OptimisticLockingIntegrationIT {
    @Autowired VersionedNoteRepository repository;
    @Autowired
    DatadamTemplate template;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    private String category;
    private final Set<String> keys = new HashSet<>();

    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(VersionedNote.class, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { category = "version-it-" + UUID.randomUUID(); }
    @AfterEach void cleanup() {
        repository.findByCategory(category).forEach(n -> keys.add(key(n.id())));
        keys.forEach(k -> template.commands().execute("DEL", k));
    }
    private String key(String id) { return "demo:versioned:" + id; }
    private String ownId() { String id = UUID.randomUUID().toString(); keys.add(key(id)); return id; }
    private VersionedNote note() { return new VersionedNote(null, UUID.randomUUID().toString(), category, " original ", null, null, null, null, null); }
    private long deadline(String id) { return ((Number) template.commands().execute("PEXPIRETIME", key(id))).longValue(); }
    private Object json(String id) { return template.commands().execute("JSON.GET", key(id)); }

    @Test void createsThenUpdatesVersionWithIdentityAuditCallbacksAndTtl() {
        var input = note();
        var first = repository.save(input);
        assertThat(input.id()).isNull(); assertThat(input.version()).isNull();
        assertThat(first.id()).isNotBlank(); assertThat(first.version()).isZero();
        assertThat(first.text()).isEqualTo("original"); assertThat(first.createdAt()).isNotNull();
        assertThat(deadline(first.id())).isPositive();
        var updated = repository.save(first.withContent(first.reference(), category, " updated "));
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.createdAt()).isEqualTo(first.createdAt());
        assertThat(updated.createdBy()).isEqualTo(first.createdBy());
        assertThat(updated.text()).isEqualTo("updated");
        assertThat(repository.findById(first.id())).contains(updated);
    }
    @Test void assignedIdWithNullVersionCreatesAndDuplicateCreationIsRejected() {
        var input = new VersionedNote(ownId(), UUID.randomUUID().toString(), category, "assigned", null, null, null, null, null);
        var first = repository.save(input); var before = json(first.id()); long expiry = deadline(first.id());
        assertThat(first.version()).isZero();
        assertThatThrownBy(() -> repository.save(input)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(json(first.id())).isEqualTo(before); assertThat(deadline(first.id())).isEqualTo(expiry);
    }
    @Test void staleSavePreservesWholeDocumentAuditExactExpiryAndNativeIndexes() {
        var first = repository.save(note());
        var winner = repository.save(first.withContent(first.reference(), category, "winner"));
        var before = json(first.id()); long expiry = deadline(first.id());
        var stale = first.withContent(UUID.randomUUID().toString(), category + "-changed", "loser");
        assertThatThrownBy(() -> repository.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stale.version()).isZero(); assertThat(json(first.id())).isEqualTo(before);
        assertThat(deadline(first.id())).isEqualTo(expiry);
        assertThat(repository.lookup("demo:versioned:reference", first.reference())).containsExactly(winner);
        assertThat(repository.lookup("demo:versioned:reference", stale.reference())).isEmpty();
        assertThat(repository.findByCategory(category)).containsExactly(winner);
        assertThat(repository.search("demo:versioned:search", new SearchQuery("@category:{$c}", Map.of("c", category), Sort.unsorted()), PageRequest.of(0, 10)).getContent()).containsExactly(winner);
        assertThat(repository.search("demo:versioned:search", new SearchQuery("@category:{$c}", Map.of("c", stale.category()), Sort.unsorted()), PageRequest.of(0, 10)).getContent()).isEmpty();
    }
    @Test void twoIndependentConnectionsCannotSaveTheSameVersion() throws Exception {
        var first = repository.save(note());
        var executor = Executors.newFixedThreadPool(2);
        try (var poolA = DatadamIntegrationIT.pool("app", "local-app-password");
             var poolB = DatadamIntegrationIT.pool("app", "local-app-password")) {
            var a = new DatadamTemplate(new DatadamCommands(poolA), mapper);
            var b = new DatadamTemplate(new DatadamCommands(poolB), mapper);
            var model = new EntityModel<>(VersionedNote.class);
            var copyA = a.findById(first.id(), model).orElseThrow();
            var copyB = b.findById(first.id(), model).orElseThrow();
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var futures = List.of(executor.submit(() -> compete(a, copyA, "A", ready, start)),
                executor.submit(() -> compete(b, copyB, "B", ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = new ArrayList<Object>(); for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(VersionedNote.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(OptimisticLockingFailureException.class::isInstance)).hasSize(1);
            var winner = (VersionedNote) results.stream().filter(VersionedNote.class::isInstance).findFirst().orElseThrow();
            assertThat(winner.version()).isEqualTo(1L);
            assertThat(repository.findById(first.id())).contains(winner);
        } finally { executor.shutdownNow(); }
    }
    private Object compete(DatadamTemplate writer, VersionedNote input, String text, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
        try { return writer.save(input.withContent(input.reference(), category, text), new EntityModel<>(VersionedNote.class)); }
        catch (OptimisticLockingFailureException conflict) { return conflict; }
    }
    @Test void uniqueRefusalDoesNotAdvanceVersionOrChangeExpiryAndIndexes() {
        var owner = repository.save(note()); var other = repository.save(note());
        var before = json(other.id()); long expiry = deadline(other.id());
        var conflict = other.withContent(owner.reference(), category, "conflict");
        assertThatThrownBy(() -> repository.save(conflict)).isInstanceOf(DuplicateKeyException.class);
        assertThat(conflict.version()).isZero(); assertThat(json(other.id())).isEqualTo(before);
        assertThat(deadline(other.id())).isEqualTo(expiry);
        assertThat(repository.lookup("demo:versioned:reference", owner.reference())).containsExactly(owner);
        assertThat(repository.lookup("demo:versioned:reference", other.reference())).containsExactly(other);
        assertThat(repository.save(other).version()).isEqualTo(1L);
    }
    @Test void removedAndExpiredEntitiesCannotBeResurrectedByAnUpdate() {
        var removed = repository.save(note()); repository.delete(removed);
        var expired = repository.save(note()); template.commands().execute("EXPIRE", key(expired.id()), 0);
        for (var absent : List.of(removed, expired)) {
            assertThatThrownBy(() -> repository.save(absent)).isInstanceOf(OptimisticLockingFailureException.class);
            assertThatThrownBy(() -> repository.delete(absent)).isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(repository.findById(absent.id())).isEmpty();
            assertThat(repository.lookup("demo:versioned:reference", absent.reference())).isEmpty();
        }
    }
    @Test void staleDeleteIsRejectedAndCurrentDeleteSucceeds() {
        var first = repository.save(note()); var next = repository.save(first);
        var before = json(first.id()); long expiry = deadline(first.id());
        assertThatThrownBy(() -> repository.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(json(first.id())).isEqualTo(before); assertThat(deadline(first.id())).isEqualTo(expiry);
        repository.delete(next); assertThat(repository.findById(first.id())).isEmpty();
        repository.deleteById(first.id()); // absent ID remains a no-op
    }
    @Test void deleteByIdProtectsAnUpdateBetweenItsReadAndDelete() {
        var first = repository.save(note()); var once = new AtomicBoolean();
        AfterConvertCallback<VersionedNote> race = (entity, context) -> {
            if (once.compareAndSet(false, true)) repository.save(entity.withContent(entity.reference(), category, "intervening"));
            return entity;
        };
        var racingTemplate = new DatadamTemplate(template.commands(), mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), EntityCallbacks.create(race), null);
        var racingRepository = new SimpleDatadamRepository<VersionedNote, String>(racingTemplate, VersionedNote.class);
        assertThatThrownBy(() -> racingRepository.deleteById(first.id())).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findById(first.id()).orElseThrow().text()).isEqualTo("intervening");
        repository.deleteById(first.id()); assertThat(repository.findById(first.id())).isEmpty();
    }
    @Test void int64VersionsRemainExactAboveLuaDoublePrecisionAndAtMaximum() throws Exception {
        var plain = new DatadamTemplate(template.commands(), mapper); var model = new EntityModel<>(VersionedNote.class);
        var seeded = new VersionedNote(ownId(), UUID.randomUUID().toString(), category, "large", 9007199254740993L, null, null, null, null);
        template.commands().execute("JSON.SET", key(seeded.id()), "$", mapper.writeValueAsString(seeded));
        var next = plain.save(seeded, model); assertThat(next.version()).isEqualTo(9007199254740994L);
        assertThatThrownBy(() -> plain.save(seeded, model)).isInstanceOf(OptimisticLockingFailureException.class);
        var nearMax = next.withVersion(Long.MAX_VALUE - 1);
        template.commands().execute("JSON.SET", key(seeded.id()), "$", mapper.writeValueAsString(nearMax));
        var max = plain.save(nearMax, model); assertThat(max.version()).isEqualTo(Long.MAX_VALUE);
        var before = json(max.id());
        assertThatIllegalArgumentException().isThrownBy(() -> plain.save(max, model));
        assertThat(json(max.id())).isEqualTo(before); plain.delete(max, model);
    }
    @Test void numericVariantsRenamedVersionAndMutableFailuresRoundTrip() {
        var plain = new DatadamTemplate(template.commands(), mapper);
        var intModel = new EntityModel<>(IntNote.class);
        var integer = plain.save(new IntNote(ownId(), null), intModel);
        assertThat(integer.version()).isZero(); assertThat(plain.save(integer, intModel).version()).isEqualTo(1);
        var primitiveModel = new EntityModel<>(PrimitiveNote.class);
        var primitive = plain.save(new PrimitiveNote(ownId(), 0), primitiveModel);
        assertThat(primitive.version()).isEqualTo(1L); assertThat(plain.save(primitive, primitiveModel).version()).isEqualTo(2L);
        var renamedModel = new EntityModel<>(RenamedNote.class);
        var renamed = plain.save(new RenamedNote(ownId(), null), renamedModel);
        assertThat(plain.save(renamed, renamedModel).version()).isEqualTo(1L);
        var mutableModel = new EntityModel<>(MutableNote.class); var mutable = new MutableNote(); mutable.id = ownId();
        plain.save(mutable, mutableModel); assertThat(mutable.version).isZero();
        var copy = plain.findById(mutable.id, mutableModel).orElseThrow(); plain.save(copy, mutableModel);
        assertThatThrownBy(() -> plain.save(mutable, mutableModel)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(mutable.version).isZero();
    }
    @Test void legacyDocumentWithoutVersionRequiresExplicitMigration() {
        String id = ownId(); template.commands().execute("JSON.SET", key(id), "$", "{\"id\":\"" + id + "\",\"text\":\"legacy\"}");
        var plain = new DatadamTemplate(template.commands(), mapper); var model = new EntityModel<>(IntNote.class);
        var before = json(id);
        assertThatThrownBy(() -> plain.save(new IntNote(id, null), model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> plain.save(new IntNote(id, 0), model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(json(id)).isEqualTo(before);
    }
    @Test void versionedUpdatesPreserveExistingExpiryWithoutAPolicyAndApplyEntityTtlWhenSpecified() {
        var plain = new DatadamTemplate(template.commands(), mapper);
        var model = new EntityModel<>(TtlNote.class);
        var first = plain.save(new TtlNote(ownId(), null, null), model);
        assertThat(deadline(first.id())).isEqualTo(-1);
        template.commands().execute("PEXPIRE", key(first.id()), 60000);
        long expiry = deadline(first.id());
        var preserved = plain.save(first, model);
        assertThat(deadline(first.id())).isEqualTo(expiry);
        var changed = plain.save(new TtlNote(first.id(), preserved.version(), 120L), model);
        long extended = deadline(first.id()); assertThat(extended).isGreaterThan(expiry + 50000);
        assertThatThrownBy(() -> plain.save(new TtlNote(first.id(), preserved.version(), 5L), model)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(deadline(first.id())).isEqualTo(extended);
        assertThat(plain.findById(first.id(), model)).contains(changed);
    }
    @Test void bulkOperationsStopOnConflictAndKeepPreviouslyAcknowledgedChanges() {
        var first = repository.save(note()); var second = repository.save(note());
        var currentSecond = repository.save(second);
        assertThatThrownBy(() -> repository.saveAll(List.of(first, second))).isInstanceOf(OptimisticLockingFailureException.class);
        var currentFirst = repository.findById(first.id()).orElseThrow();
        assertThat(currentFirst.version()).isEqualTo(1L);
        assertThat(repository.findById(second.id())).contains(currentSecond);
        assertThatThrownBy(() -> repository.deleteAll(List.of(currentFirst, second))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findById(first.id())).isEmpty();
        assertThat(repository.findById(second.id())).contains(currentSecond);
    }
    @Test void ttlAclRefusalPrecedesDocumentCreationAndVersionAssignment() {
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), mapper);
            var input = new RestrictedNote(UUID.randomUUID().toString(), null);
            assertThatThrownBy(() -> restricted.save(input, new EntityModel<>(RestrictedNote.class))).isInstanceOf(DataAccessException.class);
            assertThat(template.commands().execute("JSON.GET", "demo:temporary:" + input.id())).isNull();
            assertThat(input.version()).isNull();
        }
    }
    @Test void httpRequiresClientVersionAndReturnsConflictForStaleUpdateAndDelete() {
        var input = note();
        var created = http.postForEntity("/versioned-notes", new VersionedNoteController.NoteInput(input.reference(), category, "http", null), VersionedNote.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK); var first = created.getBody();
        assertThat(first.version()).isZero();
        String url = "/versioned-notes/" + first.id();
        var update = new VersionedNoteController.NoteInput(first.reference(), category, "updated", first.version());
        var success = http.exchange(url, HttpMethod.PUT, new HttpEntity<>(update), VersionedNote.class);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(success.getBody().version()).isEqualTo(1L);
        assertThat(http.exchange(url, HttpMethod.PUT, new HttpEntity<>(update), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(url + "?version=0", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(url, HttpMethod.PUT, new HttpEntity<>(new VersionedNoteController.NoteInput(first.reference(), category, "missing", null)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange(url + "?version=1", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @DatadamDocument(prefix = "demo:versioned:") record IntNote(@Id String id, @Version Integer version) {}
    @DatadamDocument(prefix = "demo:versioned:") record PrimitiveNote(@Id String id, @Version long version) {}
    @DatadamDocument(prefix = "demo:versioned:") record RenamedNote(@Id String id, @Version @JsonProperty("rev.no") Long version) {}
    @DatadamDocument(prefix = "demo:versioned:") record TtlNote(@Id String id, @Version Long version, @DatadamTimeToLive Long ttl) {}
    @DatadamDocument(prefix = "demo:temporary:", timeToLive = 60) record RestrictedNote(@Id String id, @Version Long version) {}
    @DatadamDocument(prefix = "demo:versioned:") static class MutableNote { @Id public String id; @Version public Long version; }
}
