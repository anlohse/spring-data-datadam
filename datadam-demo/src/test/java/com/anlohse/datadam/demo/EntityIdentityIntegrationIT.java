package com.anlohse.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EntityIdentityIntegrationIT.Generators.class)
class EntityIdentityIntegrationIT {
    @Autowired TemporaryNoteRepository repository;
    @Autowired
    DatadamTemplate template;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    private String category;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(TemporaryNote.class, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { category = "identity-it-" + UUID.randomUUID(); }
    @AfterEach void cleanup() { repository.findByCategory(category).forEach(repository::delete); }
    private TemporaryNote note() { return new TemporaryNote(null, UUID.randomUUID().toString(), category, "generated identity", null); }
    private long deadline(String id) { return ((Number) template.commands().execute("PEXPIRETIME", "demo:temporary:" + id)).longValue(); }

    @Test void recordRoundTripsGeneratedIdThroughCrudAndBothIndexFamilies() {
        var input = note();
        var saved = repository.save(input);
        assertThat(input.id()).isNull();
        assertThat(UUID.fromString(saved.id())).isNotNull();
        assertThat(repository.findById(saved.id())).contains(saved);
        assertThat(repository.findByCategory(category)).containsExactly(saved);
        assertThat(repository.lookup("demo:temporary:reference", saved.reference())).containsExactly(saved);
        var search = repository.search("demo:temporary:search", new SearchQuery("@category:{$c}", Map.of("c", category), org.springframework.data.domain.Sort.unsorted()),
            org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(search.getContent()).containsExactly(saved);
        assertThat(deadline(saved.id())).isPositive();
    }
    @Test void savingReturnedRecordUpdatesTheSameIdAndPreservesAssignedIds() {
        var saved = repository.save(note());
        var updated = repository.save(new TemporaryNote(saved.id(), saved.reference(), category, "updated", 120_000L));
        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(repository.findById(saved.id())).contains(updated);
        assertThat(repository.findByCategory(category)).hasSize(1);
        String id = "provided-" + UUID.randomUUID();
        var assigned = new TemporaryNote(id, UUID.randomUUID().toString(), category, "provided", null);
        assertThat(repository.save(assigned)).isSameAs(assigned);
        assertThat(repository.findById(id)).contains(assigned);
    }
    @Test void mutableInputReceivesItsIdAfterSuccessAndCanBeLoadedAgain() {
        var value = new MutableNote();
        value.reference = UUID.randomUUID().toString(); value.category = category; value.text = "mutable";
        var model = new EntityModel<>(MutableNote.class);
        var saved = template.save(value, model);
        assertThat(saved).isSameAs(value);
        assertThat(saved.id).isNotBlank();
        var loaded = template.findById(saved.id, model).orElseThrow();
        assertThat(loaded.id).isEqualTo(saved.id);
        assertThat(loaded.text).isEqualTo("mutable");
        assertThat(model.isNew(saved)).isFalse();
    }
    @Test void uuidAndNamedNumericStrategiesRetainTheirTypesWhenLoaded() {
        var uuidModel = new EntityModel<>(UuidNote.class);
        var uuid = template.save(new UuidNote(null, UUID.randomUUID().toString(), category, "UUID"), uuidModel);
        assertThat(uuid.id()).isInstanceOf(UUID.class);
        assertThat(template.findById(uuid.id(), uuidModel)).contains(uuid);
        var numericModel = new EntityModel<>(NumericNote.class);
        var numeric = template.save(new NumericNote(null, UUID.randomUUID().toString(), category, "numeric"), numericModel);
        assertThat(numeric.id()).isPositive();
        assertThat(template.findById(numeric.id(), numericModel)).contains(numeric);
    }
    @Test void saveAllReturnsEveryGeneratedRecordAndKeepsOriginalsUnchanged() {
        var inputs = List.of(note(), note(), note());
        var saved = repository.saveAll(inputs);
        assertThat(saved).extracting(TemporaryNote::id).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(inputs).extracting(TemporaryNote::id).containsOnlyNulls();
        assertThat(repository.findAllById(saved.stream().map(TemporaryNote::id).toList())).containsExactlyElementsOf(saved);
    }
    @Test void collisionNeverOverwritesTheDocumentOrChangesItsDeadlineWithOrWithoutTtl() {
        var owner = repository.save(note());
        long before = deadline(owner.id());
        var collision = new DatadamTemplate(template.commands(), mapper, Map.of("collisionIds", context -> owner.id()));
        assertThatThrownBy(() -> collision.save(new Collision(null, UUID.randomUUID().toString(), category, "must not replace"), new EntityModel<>(Collision.class)))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> collision.save(new TtlCollision(null, UUID.randomUUID().toString(), category, "must not renew"), new EntityModel<>(TtlCollision.class)))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.findById(owner.id())).contains(owner);
        assertThat(deadline(owner.id())).isEqualTo(before);
    }
    @Test void uniqueRefusalsDoNotAssignIdsOrCreateAdditionalDocuments() {
        var owner = repository.save(note());
        long before = deadline(owner.id());
        var record = new TemporaryNote(null, owner.reference(), category, "duplicate", null);
        assertThatThrownBy(() -> repository.save(record)).isInstanceOf(DuplicateKeyException.class);
        assertThat(record.id()).isNull();
        var mutable = new MutableNote();
        mutable.reference = owner.reference(); mutable.category = category; mutable.text = "duplicate";
        assertThatThrownBy(() -> template.save(mutable, new EntityModel<>(MutableNote.class))).isInstanceOf(DuplicateKeyException.class);
        assertThat(mutable.id).isNull();
        assertThat(repository.findByCategory(category)).containsExactly(owner);
        assertThat(deadline(owner.id())).isEqualTo(before);
    }
    @Test void permissionFailureDoesNotAssignTheGeneratedId() {
        var value = new MutableNote();
        value.reference = UUID.randomUUID().toString(); value.category = category; value.text = "denied";
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), mapper);
            assertThatThrownBy(() -> restricted.save(value, new EntityModel<>(MutableNote.class)))
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        }
        assertThat(value.id).isNull();
        assertThat(repository.findByCategory(category)).isEmpty();
    }
    @Test void persistableSupportsExplicitNewStateForAssignedIds() {
        var model = new EntityModel<>(AssignedNote.class);
        var input = new AssignedNote("assigned-" + UUID.randomUUID(), UUID.randomUUID().toString(), category, "first", true);
        template.save(input, model);
        assertThatThrownBy(() -> template.save(input, model)).isInstanceOf(DuplicateKeyException.class);
        var loaded = template.findById(input.id(), model).orElseThrow();
        assertThat(model.isNew(loaded)).isFalse();
        var update = new AssignedNote(input.id(), input.reference(), category, "update", false);
        assertThat(template.save(update, model)).isEqualTo(update);
        assertThat(template.findById(input.id(), model).orElseThrow().text()).isEqualTo("update");
    }
    @Test void concurrentGenerationProducesDistinctKeysAndNativeUniqueLookups() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<TemporaryNote>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> repository.save(note())));
            List<String> ids = new ArrayList<>();
            for (var future : futures) {
                var saved = future.get(15, TimeUnit.SECONDS);
                ids.add(saved.id());
                assertThat(repository.lookup("demo:temporary:reference", saved.reference())).containsExactly(saved);
            }
            assertThat(ids).hasSize(20).doesNotHaveDuplicates();
        } finally { executor.shutdownNow(); }
    }
    @Test void httpSaveWithoutIdReturnsThePersistedIdentity() {
        var response = http.postForEntity("/temporary-notes", note(), TemporaryNote.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = response.getBody();
        assertThat(saved.id()).isNotBlank();
        assertThat(http.getForObject("/temporary-notes/" + saved.id(), TemporaryNote.class)).isEqualTo(saved);
    }
    @Test void simultaneousClaimsOfTheSameGeneratedIdHaveExactlyOneWinner() throws Exception {
        String fixedId = "collision-" + UUID.randomUUID();
        var collision = new DatadamTemplate(template.commands(), mapper, Map.of("collisionIds", context -> fixedId));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TtlCollision>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                var input = new TtlCollision(null, UUID.randomUUID().toString(), category, "candidate-" + i);
                futures.add(executor.submit(() -> {
                    start.await();
                    try { return collision.save(input, new EntityModel<>(TtlCollision.class)); }
                    catch (DuplicateKeyException e) { return null; }
                }));
            }
            start.countDown();
            List<TtlCollision> winners = new ArrayList<>();
            for (var future : futures) { var result = future.get(10, TimeUnit.SECONDS); if (result != null) winners.add(result); }
            assertThat(winners).hasSize(1);
            var stored = repository.findById(fixedId).orElseThrow();
            assertThat(stored.reference()).isEqualTo(winners.get(0).reference());
            assertThat(repository.findByCategory(category)).hasSize(1);
            assertThat(deadline(fixedId)).isPositive();
        } finally { executor.shutdownNow(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Generators {
        @Bean("identityTestIds") DatadamIdGenerator ids() {
            var next = new AtomicLong(System.currentTimeMillis() * 1000);
            return context -> next.incrementAndGet();
        }
    }
    @DatadamDocument(prefix = "demo:temporary:", timeToLive = 60)
    public static class MutableNote {
        @Id @DatadamGeneratedId
        public String id;
        public String reference;
        public String category;
        public String text;
    }
    @DatadamDocument(prefix = "demo:temporary:")
    record UuidNote(@Id @DatadamGeneratedId UUID id, String reference, String category, String text) {}
    @DatadamDocument(prefix = "demo:temporary:")
    record NumericNote(@Id @DatadamGeneratedId(generator = "identityTestIds") Long id, String reference, String category, String text) {}
    @DatadamDocument(prefix = "demo:temporary:")
    record Collision(@Id @DatadamGeneratedId(generator = "collisionIds") String id, String reference, String category, String text) {}
    @DatadamDocument(prefix = "demo:temporary:", timeToLive = 300)
    record TtlCollision(@Id @DatadamGeneratedId(generator = "collisionIds") String id, String reference, String category, String text) {}
    @DatadamDocument(prefix = "demo:temporary:")
    record AssignedNote(@Id String id, String reference, String category, String text, @JsonIgnore boolean fresh) implements Persistable<String> {
        @Override @JsonIgnore public String getId() { return id; }
        @Override @JsonIgnore public boolean isNew() { return fresh; }
    }
}
