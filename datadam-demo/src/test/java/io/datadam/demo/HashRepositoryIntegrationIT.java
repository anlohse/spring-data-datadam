package io.datadam.demo;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.SimpleDatadamRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class HashRepositoryIntegrationIT {
    @Autowired DatadamTemplate template;
    @Autowired HashNoteRepository repository;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    final List<String> ownedKeys = new ArrayList<>();
    final EntityModel<HashNote> model = new EntityModel<>(HashNote.class);
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            // Fixture only: automatic HASH schema management belongs to 4.2.
            try { new DatadamCommands(pool).execute("IDX.CREATE", "demo:hash:test-reference", "ON", "HASH", "PREFIX", 1, "demo:hash:note:", "SCHEMA", "reference", "TAG", "UNIQUE"); }
            catch (InvalidDataAccessResourceUsageException e) { if (!e.getMessage().equals("ERR Index already exists")) throw e; }
            new DatadamIndexOperations(new DatadamCommands(pool)).awaitReady("demo:hash:test-reference", java.time.Duration.ofSeconds(30));
            try { new DatadamCommands(pool).execute("IDX.CREATE", "demo:hash:test-pair", "ON", "HASH", "PREFIX", 1, "demo:hash:plain:", "SCHEMA", "state", "TAG", "enabled", "TAG", "UNIQUE"); }
            catch (InvalidDataAccessResourceUsageException e) { if (!e.getMessage().equals("ERR Index already exists")) throw e; }
            new DatadamIndexOperations(new DatadamCommands(pool)).awaitReady("demo:hash:test-pair", java.time.Duration.ofSeconds(30));
        }
    }
    @AfterEach void cleanup() { ownedKeys.forEach(k -> template.commands().execute("DEL", k)); }
    HashNote create() {
        var saved = repository.save(new HashNote(null, UUID.randomUUID().toString(), " original ", List.of("java", "datadam"), Map.of("nested", Map.of("enabled", true)), null, null, null, null, null));
        ownedKeys.add(model.key(saved.id())); return saved;
    }
    long deadline(String key) { return ((Number) template.commands().execute("PEXPIRETIME", key)).longValue(); }
    Object fields(String key) { return template.commands().execute("HGETALL", key); }
    @Test void generatedRecordRoundTripsAsHashWithAuditVersionAndCallbacks() {
        var first = create();
        assertThat(first.version()).isZero(); assertThat(first.text()).isEqualTo("original");
        assertThat(first.createdAt()).isNotNull(); assertThat(first.createdBy()).isEqualTo("demo-application");
        assertThat(repository.findById(first.id())).contains(first); assertThat(deadline(model.key(first.id()))).isPositive();
        var raw = DatadamCommands.map(fields(model.key(first.id())));
        assertThat(raw).containsEntry("version", "0").containsEntry("text", "original").containsEntry("tags", "[\"java\",\"datadam\"]");
        assertThat(raw.get("details").toString()).contains("nested");
        var next = repository.save(first.withContent(first.reference(), " updated ", first.tags(), first.details(), first.version()));
        assertThat(next.version()).isEqualTo(1L); assertThat(next.text()).isEqualTo("updated");
        assertThat(next.createdAt()).isEqualTo(first.createdAt()); assertThat(repository.findById(first.id())).contains(next);
    }
    @Test void nullFieldsAndUnknownOldFieldsAreRemovedByWholeReplacement() {
        var first = create(); String key = model.key(first.id());
        var raw = DatadamCommands.map(fields(key)); var args = new ArrayList<Object>(); args.add(key);
        raw.forEach((k,v) -> { args.add(k); args.add(v); }); args.add("obsolete"); args.add("external");
        template.commands().execute("HREPLACE", args.toArray());
        var next = repository.save(first.withContent(first.reference(), null, null, null, first.version()));
        var after = DatadamCommands.map(fields(key)); assertThat(after).doesNotContainKeys("text", "tags", "details", "obsolete");
        assertThat(repository.findById(next.id())).contains(next);
    }
    @Test void uniqueFailureLeavesFullHashVersionAuditAndDeadlineUnchanged() {
        var owner = create(); var other = create(); String key = model.key(other.id()); Object before = fields(key); long expiry = deadline(key);
        assertThatThrownBy(() -> repository.save(other.withContent(owner.reference(), "refused", null, null, other.version()))).isInstanceOf(DuplicateKeyException.class);
        assertThat(fields(key)).isEqualTo(before); assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(repository.findById(other.id())).contains(other); assertThat(repository.findById(owner.id())).contains(owner);
        assertThat(repository.save(other).version()).isEqualTo(1L);
    }
    @Test void staleUpdateAndDeleteAreRejectedWithoutResettingTtl() {
        var first = create(); var current = repository.save(first); String key = model.key(first.id()); Object before = fields(key); long expiry = deadline(key);
        assertThatThrownBy(() -> repository.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> repository.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(fields(key)).isEqualTo(before); assertThat(deadline(key)).isEqualTo(expiry);
        repository.delete(current); assertThat(repository.findById(first.id())).isEmpty();
        assertThatThrownBy(() -> repository.save(current)).isInstanceOf(OptimisticLockingFailureException.class);
    }
    @Test void uniqueValidationSeesTheFinalFieldSetWithoutAnIntermediateConflict() {
        var plain = new SimpleDatadamRepository<Plain, String>(template, Plain.class);
        String owner = UUID.randomUUID().toString(); String other = UUID.randomUUID().toString();
        ownedKeys.add("demo:hash:plain:"+owner); ownedKeys.add("demo:hash:plain:"+other);
        plain.save(new Plain(owner, "owner", null, true, State.INACTIVE, null, null, null, null));
        plain.save(new Plain(other, "other", null, false, State.INACTIVE, null, null, null, null));
        // HSET enabled=true followed by HDEL state would temporarily claim the owner's tuple.
        var finalState = new Plain(other, "updated", null, true, null, null, null, null, null);
        plain.save(finalState);
        assertThat(plain.findById(other)).contains(finalState);
        assertThat(DatadamCommands.map(fields("demo:hash:plain:"+other))).doesNotContainKey("state");
    }
    @Test void mutableHashReceivesGeneratedIdVersionAndAuditOnlyOnAcknowledgedSave() {
        var hashModel = new EntityModel<>(MutableHash.class); var input = new MutableHash(); input.text="mutable";
        template.save(input, hashModel); ownedKeys.add(hashModel.key(input.id));
        assertThat(input.id).isNotBlank(); assertThat(input.version).isEqualTo(1L); assertThat(input.createdAt).isNotNull();
        var copy = template.findById(input.id, hashModel).orElseThrow(); template.save(copy, hashModel);
        assertThat(copy.version).isEqualTo(2L);
        assertThatThrownBy(() -> template.save(input, hashModel)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(input.version).isEqualTo(1L);
        assertThat(template.findById(input.id, hashModel).orElseThrow().version).isEqualTo(2L);
    }
    @Test void independentConnectionsHaveOneWinnerForTheSameHashVersion() throws Exception {
        var first = create(); var executor = Executors.newFixedThreadPool(2);
        try (var poolA = DatadamIntegrationIT.pool("app", "local-app-password"); var poolB = DatadamIntegrationIT.pool("app", "local-app-password")) {
            var a = new DatadamTemplate(new DatadamCommands(poolA), mapper); var b = new DatadamTemplate(new DatadamCommands(poolB), mapper);
            var readA = a.findById(first.id(), model).orElseThrow(); var readB = b.findById(first.id(), model).orElseThrow();
            var start = new CountDownLatch(1);
            var futures = List.of(executor.submit(() -> compete(a, readA, "A", start)), executor.submit(() -> compete(b, readB, "B", start)));
            start.countDown(); var results = new ArrayList<Object>(); for (var f : futures) results.add(f.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(HashNote.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(OptimisticLockingFailureException.class::isInstance)).hasSize(1);
            assertThat(repository.findById(first.id()).orElseThrow().version()).isEqualTo(1L);
        } finally { executor.shutdownNow(); }
    }
    Object compete(DatadamTemplate writer, HashNote note, String text, CountDownLatch start) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
        try { return writer.save(note.withContent(note.reference(), text, note.tags(), note.details(), note.version()), model); }
        catch (OptimisticLockingFailureException e) { return e; }
    }
    @Test void expirationCannotBeUndoneBySavingAStaleVersion() {
        var first = create(); template.commands().execute("EXPIRE", model.key(first.id()), 0);
        assertThatThrownBy(() -> repository.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> repository.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findById(first.id())).isEmpty(); repository.deleteById(first.id());
    }
    @Test void plainHashCrudSupportsTypedValuesTtlPolicyAndSequentialBatches() {
        var plain = new SimpleDatadamRepository<Plain, String>(template, Plain.class);
        String id = UUID.randomUUID().toString(); String key = "demo:hash:plain:"+id; ownedKeys.add(key);
        var input = new Plain(id, "null", null, true, State.ACTIVE, new BigDecimal("1234567890.123456789012300"), List.of("ação"), new Settings("SP", List.of(1,2)), null);
        assertThat(plain.save(input)).isEqualTo(input); assertThat(plain.findById(id)).contains(input); assertThat(deadline(key)).isEqualTo(-1);
        template.commands().execute("PEXPIRE", key, 120000); long expiry = deadline(key);
        plain.save(input); assertThat(deadline(key)).isEqualTo(expiry);
        plain.save(new Plain(id, null, 42, false, State.INACTIVE, input.balance(), null, null, 180L));
        assertThat(deadline(key)).isGreaterThan(expiry+50000); assertThat(DatadamCommands.map(fields(key))).doesNotContainKeys("text", "tags", "settings");
        assertThat(plain.findAllById(List.of(id))).hasSize(1); assertThat(plain.findAll()).extracting(Plain::id).contains(id);
        assertThat(plain.count()).isPositive(); assertThat(plain.persist(id)).isTrue(); assertThat(deadline(key)).isEqualTo(-1);
        plain.deleteAllById(List.of(id)); assertThat(plain.existsById(id)).isFalse();
    }
    @Test void hashAndJsonWithSameIdHaveIndependentKeyspacesAndWrongTypeIsNeverReplaced() {
        var first = create(); var jsonModel = new EntityModel<>(JsonTwin.class);
        String key = "demo:hash:json:"+first.id(); ownedKeys.add(key);
        template.save(new JsonTwin(first.id(), "json"), jsonModel);
        assertThat(template.findById(first.id(), jsonModel)).contains(new JsonTwin(first.id(), "json"));
        assertThat(repository.findById(first.id())).contains(first);
        assertThat(repository.findAll()).contains(first); assertThat(repository.findAll()).extracting(HashNote::id).contains(first.id());
        String collision = "collision-"+UUID.randomUUID(); String collisionKey = "demo:hash:plain:"+collision; ownedKeys.add(collisionKey);
        template.commands().execute("JSON.SET", collisionKey, "$", "{\"id\":\"safe\"}");
        var wrong = new SimpleDatadamRepository<Plain, String>(template, Plain.class);
        assertThatThrownBy(() -> wrong.findById(collision)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThatThrownBy(() -> wrong.deleteById(collision)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThatThrownBy(() -> wrong.save(new Plain(collision, null, null, false, null, null, null, null, null))).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(template.commands().execute("JSON.GET", collisionKey)).isEqualTo("{\"id\":\"safe\"}");
    }
    @Test void aclTtlRefusalPrecedesHashCreationAndGeneratedValues() {
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), mapper);
            var input = new HashNote(null, UUID.randomUUID().toString(), "x", null, null, null, null, null, null, null);
            assertThatThrownBy(() -> restricted.save(input, model)).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThat(input.id()).isNull(); assertThat(input.version()).isNull();
        }
    }
    @Test void int64HashVersionsRemainExactAboveDoublePrecision() {
        var first = create(); String key = model.key(first.id());
        var values = DatadamCommands.map(fields(key)); values.put("version", "9007199254740993");
        var args = new ArrayList<Object>(); args.add(key); values.forEach((k,v)->{args.add(k);args.add(v);}); template.commands().execute("HREPLACE", args.toArray());
        var loaded = repository.findById(first.id()).orElseThrow(); assertThat(loaded.version()).isEqualTo(9007199254740993L);
        assertThat(repository.save(loaded).version()).isEqualTo(9007199254740994L);
        assertThatThrownBy(() -> repository.save(loaded)).isInstanceOf(OptimisticLockingFailureException.class);
    }
    @Test void durationTtlRoundTripsThroughHashWithoutChangingItsUnit() {
        var hashModel = new EntityModel<>(DurationHash.class); String id=UUID.randomUUID().toString(); ownedKeys.add(hashModel.key(id));
        var input = new DurationHash(id, java.time.Duration.ofSeconds(60)); template.save(input, hashModel);
        assertThat(template.findById(id, hashModel)).contains(input);
        long ttl = ((Number) template.commands().execute("PTTL", hashModel.key(id))).longValue();
        assertThat(ttl).isBetween(55000L, 60000L);
    }
    @Test void httpCreatesUpdatesAndDeletesHashWithClientVersion() {
        var request = new HashNoteController.Input(UUID.randomUUID().toString(), " http ", List.of("hash"), Map.of("nested", true), null);
        var created = http.postForEntity("/hash-notes", request, HashNote.class); assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        var first = created.getBody(); ownedKeys.add(model.key(first.id())); String url="/hash-notes/"+first.id();
        var update = new HashNoteController.Input(first.reference(), null, null, null, first.version());
        var next = http.exchange(url, HttpMethod.PUT, new HttpEntity<>(update), HashNote.class);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(next.getBody().version()).isEqualTo(1L);
        assertThat(http.exchange(url, HttpMethod.PUT, new HttpEntity<>(update), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(url+"?version=0", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(url+"?version=1", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    enum State { ACTIVE, INACTIVE }
    record Settings(String city, List<Integer> numbers) {}
    @DatadamDocument(prefix="demo:hash:plain:", storage=DatadamStorage.HASH)
    record Plain(@Id String id, String text, Integer optional, boolean enabled, State state, BigDecimal balance,
        List<String> tags, Settings settings, @DatadamTimeToLive Long ttl) {}
    @DatadamDocument(prefix="demo:hash:json:") record JsonTwin(@Id String id, String text) {}
    @DatadamDocument(prefix="demo:hash:mutable:", storage=DatadamStorage.HASH, timeToLive=60)
    static class MutableHash { @Id @DatadamGeneratedId public String id; @Version public long version; public String text; @CreatedDate public java.time.Instant createdAt; }
    @DatadamDocument(prefix="demo:hash:duration:", storage=DatadamStorage.HASH) record DurationHash(@Id String id, @DatadamTimeToLive java.time.Duration ttl) {}
}
