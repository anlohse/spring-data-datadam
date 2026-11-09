package com.anlohse.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JsonMutationIntegrationIT {
    @Autowired
    DatadamTemplate template;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    private final List<String> ownedKeys = new ArrayList<>();
    private final EntityModel<Doc> model = new EntityModel<>(Doc.class);
    private final JsonPath<Number> number = JsonPath.of("$.number", Number.class);
    private final JsonPath<List<String>> tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
    private DatadamJsonOperations<Doc> ops;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(Doc.class, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { ops = template.json(Doc.class); }
    @AfterEach void cleanup() { ownedKeys.forEach(key -> template.commands().execute("DEL", key)); }
    private Doc doc(long n) {
        String id = UUID.randomUUID().toString(); ownedKeys.add("demo:mutation:" + id);
        template.save(new Doc(id, n, List.of("tag-" + id), Map.of("code", "code-" + id, "nested", Map.of("keep", true, "remove", "old"))), model);
        return template.findById(id, model).orElseThrow();
    }
    private long deadline(Doc doc) { return ((Number) template.commands().execute("PEXPIRETIME", model.key(doc.id()))).longValue(); }
    private Object json(Doc doc) { return template.commands().execute("JSON.GET", model.key(doc.id())); }
    private List<Doc> lookup(String index, Object value) { return template.lookup(index, model, value); }
    private List<Doc> search(String expression, Map<String, Object> parameters) {
        return template.search("demo:mutation:search", new SearchQuery(expression, parameters, Sort.unsorted()), PageRequest.of(0, 10), model).getContent();
    }
    @Test void arithmeticUpdatesNumericIndexesAndPreservesExactTtl() {
        var doc = doc(10); long expiry = deadline(doc);
        assertThat(ops.increment(doc.id(), number, 2).get(0).longValue()).isEqualTo(12);
        assertThat(lookup("demo:mutation:number", 10)).isEmpty(); assertThat(lookup("demo:mutation:number", 12)).hasSize(1);
        assertThat(search("@number:[12 12]", Map.of())).hasSize(1);
        assertThat(ops.multiply(doc.id(), JsonPath.of(".number", Number.class), 0.5).get(0).doubleValue()).isEqualTo(6);
        assertThat(search("@number:[12 12]", Map.of())).isEmpty(); assertThat(search("@number:[6 6]", Map.of())).hasSize(1);
        assertThat(deadline(doc)).isEqualTo(expiry);
    }
    @Test void twoIndependentConnectionsDoNotLoseConcurrentIncrements() throws Exception {
        var doc = doc(1000); long expiry = deadline(doc); var executor = Executors.newFixedThreadPool(2);
        try (var poolA = DatadamIntegrationIT.pool("app", "local-app-password"); var poolB = DatadamIntegrationIT.pool("app", "local-app-password")) {
            var a = new DatadamTemplate(new DatadamCommands(poolA), mapper).json(Doc.class);
            var b = new DatadamTemplate(new DatadamCommands(poolB), mapper).json(Doc.class);
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (var writer : List.of(a, b)) futures.add(executor.submit(() -> {
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                for (int i = 0; i < 40; i++) writer.increment(doc.id(), number, 1);
            }));
            start.countDown(); for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(ops.read(doc.id(), number).values().get(0).longValue()).isEqualTo(1080);
            assertThat(lookup("demo:mutation:number", 1080)).hasSize(1); assertThat(deadline(doc)).isEqualTo(expiry);
        } finally { executor.shutdownNow(); }
    }
    @Test void arrayAppendInsertPopAndTrimMaintainUniqueAndFtEntries() {
        var doc = doc(10); long expiry = deadline(doc); String added = "added-" + doc.id(); String inserted = "inserted-" + doc.id();
        assertThat(ops.arrayAppend(doc.id(), tags, List.of(added))).containsExactly(2L);
        assertThat(lookup("demo:mutation:tags", added)).hasSize(1); assertThat(search("@tag:{$t}", Map.of("t", added))).hasSize(1);
        assertThat(ops.arrayInsert(doc.id(), tags, 1, List.of(inserted))).containsExactly(3L);
        assertThat(ops.arrayIndex(doc.id(), tags, inserted)).containsExactly(1L); assertThat(lookup("demo:mutation:tags", inserted)).hasSize(1);
        assertThat(ops.arrayLength(doc.id(), tags)).containsExactly(3L);
        assertThat(ops.arrayPop(doc.id(), tags, 1)).containsExactly(new JsonPopResult<>(true, inserted));
        assertThat(lookup("demo:mutation:tags", inserted)).isEmpty(); assertThat(search("@tag:{$t}", Map.of("t", inserted))).isEmpty();
        assertThat(ops.arrayTrim(doc.id(), tags, 1, -1)).containsExactly(1L);
        assertThat(lookup("demo:mutation:tags", doc.tags().get(0))).isEmpty(); assertThat(lookup("demo:mutation:tags", added)).hasSize(1);
        assertThat(search("@tag:{$t}", Map.of("t", doc.tags().get(0)))).isEmpty(); assertThat(deadline(doc)).isEqualTo(expiry);
    }
    @Test void mergeRecursesRemovesNullMembersAndUpdatesIdxAndFt() {
        var doc = doc(10); long expiry = deadline(doc); String code = "changed-" + doc.id();
        Map<String, Object> nested = new HashMap<>(); nested.put("remove", null); nested.put("added", 42);
        assertThat(ops.merge(doc.id(), JsonPath.of("$.details", Map.class), Map.of("code", code, "nested", nested))).isTrue();
        var detail = ops.read(doc.id(), JsonPath.of("$.details", new TypeReference<Map<String, Object>>() {})).values().get(0);
        assertThat((Map<?, ?>) detail.get("nested")).hasSize(2); assertThat(detail.get("nested").toString()).contains("keep=true", "added=42").doesNotContain("remove");
        assertThat(lookup("demo:mutation:code", doc.details().get("code"))).isEmpty(); assertThat(lookup("demo:mutation:code", code)).hasSize(1);
        assertThat(search("@code:{$c}", Map.of("c", code))).hasSize(1); assertThat(deadline(doc)).isEqualTo(expiry);
        assertThat(ops.merge(doc.id(), JsonPath.of("$.absent", Map.class), Map.of("x", 1))).isTrue();
        assertThat(ops.read(doc.id(), JsonPath.of("$.absent", Map.class)).values()).isEmpty();
        assertThatThrownBy(() -> ops.merge(doc.id(), JsonPath.of(".absent", Map.class), Map.of("x", 1))).isInstanceOf(InvalidDataAccessResourceUsageException.class);
    }
    @Test void uniqueFailuresInArithmeticArrayAndMergePreserveWholeDocumentAndDeadline() {
        var owner = doc(11); var other = doc(10); Object before = json(other); long expiry = deadline(other);
        List<Runnable> conflicts = List.of(() -> ops.increment(other.id(), number, 1),
            () -> ops.multiply(other.id(), number, 1.1),
            () -> ops.arrayAppend(other.id(), tags, owner.tags()),
            () -> ops.arrayInsert(other.id(), tags, 0, owner.tags()),
            () -> ops.merge(other.id(), JsonPath.of("$.details", Map.class), Map.of("code", owner.details().get("code"))));
        for (var conflict : conflicts) {
            assertThatThrownBy(conflict::run).isInstanceOf(DuplicateKeyException.class);
            assertThat(json(other)).isEqualTo(before); assertThat(deadline(other)).isEqualTo(expiry);
            assertThat(lookup("demo:mutation:number", 10)).containsExactly(other); assertThat(lookup("demo:mutation:number", 11)).containsExactly(owner);
            assertThat(lookup("demo:mutation:tags", owner.tags().get(0))).containsExactly(owner);
            assertThat(lookup("demo:mutation:code", other.details().get("code"))).containsExactly(other);
        }
    }
    @Test void popAndTrimReleaseArrayMemberOwnership() {
        var owner = doc(10); var other = doc(20);
        assertThat(ops.arrayPop(owner.id(), tags)).hasSize(1);
        assertThat(ops.arrayAppend(other.id(), tags, owner.tags())).containsExactly(2L);
        assertThat(ops.arrayTrim(other.id(), tags, 2, 1)).containsExactly(0L);
        assertThat(lookup("demo:mutation:tags", owner.tags().get(0))).isEmpty();
    }
    @Test void popAndTrimRespectUniqueIndexesOnArrayPositions() {
        var owner = doc(10); var other = doc(20);
        var slots = JsonPath.of("$.slots", new TypeReference<List<String>>() {});
        String taken = "taken-" + owner.id(); String free = "free-" + other.id();
        ops.set(owner.id(), slots, List.of(taken)); ops.set(other.id(), slots, List.of(free, taken));
        Object before = json(other); long expiry = deadline(other);
        for (Runnable conflict : List.<Runnable>of(() -> ops.arrayPop(other.id(), slots, 0), () -> ops.arrayTrim(other.id(), slots, 1, -1))) {
            assertThatThrownBy(conflict::run).isInstanceOf(DuplicateKeyException.class);
            assertThat(json(other)).isEqualTo(before); assertThat(deadline(other)).isEqualTo(expiry);
            assertThat(lookup("demo:mutation:first", taken)).extracting(Doc::id).containsExactly(owner.id());
            assertThat(lookup("demo:mutation:first", free)).extracting(Doc::id).containsExactly(other.id());
        }
    }
    @Test void concurrentArrayAppendsAndDisjointMergeMembersArePreserved() throws Exception {
        var doc = doc(1000); long expiry = deadline(doc); var executor = Executors.newFixedThreadPool(2);
        try (var poolA = DatadamIntegrationIT.pool("app", "local-app-password"); var poolB = DatadamIntegrationIT.pool("app", "local-app-password")) {
            var writers = List.of(new DatadamTemplate(new DatadamCommands(poolA), mapper).json(Doc.class),
                new DatadamTemplate(new DatadamCommands(poolB), mapper).json(Doc.class));
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                final int actor = i; var writer = writers.get(i);
                futures.add(executor.submit(() -> {
                    try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    for (int n = 0; n < 20; n++) {
                        writer.arrayAppend(doc.id(), tags, List.of(actor + "-" + n + "-" + doc.id()));
                        writer.merge(doc.id(), JsonPath.of("$.details", Map.class), Map.of("actor" + actor, n));
                    }
                }));
            }
            start.countDown(); for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(ops.arrayLength(doc.id(), tags)).containsExactly(41L);
            var details = ops.read(doc.id(), JsonPath.of("$.details", new TypeReference<Map<String, Object>>() {})).values().get(0);
            assertThat(details).containsEntry("actor0", 19).containsEntry("actor1", 19);
            assertThat(lookup("demo:mutation:tags", "1-19-" + doc.id())).hasSize(1); assertThat(deadline(doc)).isEqualTo(expiry);
        } finally { executor.shutdownNow(); }
    }
    @Test void nullPopEmptyArraysAndWrongTypeResultsAreDistinct() {
        var doc = doc(10); ops.set(doc.id(), tags, Arrays.asList((String) null));
        assertThat(ops.arrayPop(doc.id(), tags)).containsExactly(new JsonPopResult<String>(true, null));
        assertThat(ops.arrayPop(doc.id(), tags)).containsExactly(new JsonPopResult<String>(false, null));
        assertThat(ops.arrayAppend(doc.id(), tags, List.of("x-" + doc.id()))).containsExactly(1L);
        assertThat(ops.arrayInsert(doc.id(), tags, 1, List.of("y-" + doc.id()))).containsExactly(2L);
        var scalar = JsonPath.of("$.number", new TypeReference<List<String>>() {});
        assertThat(ops.arrayLength(doc.id(), scalar)).containsExactly((Long) null);
        assertThat(ops.arrayPop(doc.id(), scalar)).containsExactly(new JsonPopResult<String>(false, null));
        assertThat(ops.increment(doc.id(), JsonPath.of("$.tags", Number.class), 1)).containsExactly((Number) null);
        assertThatThrownBy(() -> ops.increment(doc.id(), JsonPath.of(".tags", Number.class), 1)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(ops.arrayLength(doc.id(), JsonPath.of("$.absent", new TypeReference<List<String>>() {}))).isEmpty();
    }
    @Test void invalidInsertAndNonFiniteResultsPreserveStoredData() {
        var doc = doc(10); Object before = json(doc); long expiry = deadline(doc);
        assertThatThrownBy(() -> ops.arrayInsert(doc.id(), tags, 99, List.of("x"))).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(json(doc)).isEqualTo(before); assertThat(deadline(doc)).isEqualTo(expiry);
        ops.set(doc.id(), number, Double.MAX_VALUE); Object large = json(doc);
        assertThatThrownBy(() -> ops.multiply(doc.id(), number, 2)).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(json(doc)).isEqualTo(large); assertThat(deadline(doc)).isEqualTo(expiry);
    }
    @Test void integerOverflowPromotesRatherThanTruncatingToThePathType() {
        var doc = doc(Long.MAX_VALUE);
        assertThat(ops.increment(doc.id(), JsonPath.of("$.number", Long.class), 1).get(0)).isInstanceOf(Double.class);
        assertThat(ops.read(doc.id(), number).values().get(0).doubleValue()).isGreaterThan(9e18);
    }
    @Test void conversionFailureAfterPopReportsAnAlreadySubmittedMutation() {
        var doc = doc(10); ops.set(doc.id(), JsonPath.of("$.extra", new TypeReference<List<Integer>>() {}), List.of(1, 2));
        assertThatThrownBy(() -> ops.arrayPop(doc.id(), JsonPath.of("$.extra", new TypeReference<List<String>>() {})))
            .isInstanceOf(JsonMutationDecodingException.class);
        assertThat(ops.read(doc.id(), JsonPath.of("$.extra", new TypeReference<List<Integer>>() {})).values()).containsExactly(List.of(1));
    }
    @Test void sequentialCommandsKeepEarlierSuccessWhenALaterCommandFails() {
        var owner = doc(11); var other = doc(10); String addition = "survives-" + other.id();
        ops.arrayAppend(other.id(), tags, List.of(addition));
        assertThatThrownBy(() -> ops.increment(other.id(), number, 1)).isInstanceOf(DuplicateKeyException.class);
        assertThat(ops.read(other.id(), tags).values().get(0)).contains(addition);
        assertThat(lookup("demo:mutation:number", owner.number())).containsExactly(owner);
    }
    @Test void missingExpiredAndAclDeniedKeysAreNotMutated() {
        var doc = doc(10); template.commands().execute("EXPIRE", model.key(doc.id()), 0);
        for (Runnable mutation : List.<Runnable>of(() -> ops.increment(doc.id(), number, 1), () -> ops.arrayAppend(doc.id(), tags, List.of("x")),
            () -> ops.arrayPop(doc.id(), tags), () -> ops.merge(doc.id(), JsonPath.of("$.details", Map.class), Map.of("x", 1))))
            assertThatThrownBy(mutation::run).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(template.findById(doc.id(), model)).isEmpty();
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), mapper).json(TemporaryNote.class);
            assertThatThrownBy(() -> restricted.increment("denied", number, 1)).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> restricted.arrayAppend("denied", tags, List.of("x"))).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> restricted.merge("denied", JsonPath.of("$.details", Map.class), Map.of())).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }
    @Test void httpExampleMutatesAgeTagsAndNestedDetails() {
        String id = UUID.randomUUID().toString(); ownedKeys.add("demo:customer:" + id);
        var input = new Customer(id, "Ana", id + "@example.test", "demo", 30, List.of("java"));
        assertThat(http.postForEntity("/customers", input, Customer.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = "/customers/" + id + "/json";
        assertThat(http.postForObject(url + "/age/increment", Map.of("amount", 2), String.class)).isEqualTo("[32]");
        assertThat(http.postForObject(url + "/tags/append", Map.of("values", List.of("datadam")), String.class)).isEqualTo("[2]");
        assertThat(http.exchange(url + "/tags/pop", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getBody()).contains("\"popped\":true", "datadam");
        http.put(url + "/details", Map.of("keep", true, "remove", "old"));
        Map<String, Object> patch = new HashMap<>(); patch.put("remove", null); patch.put("added", 42);
        assertThat(http.exchange(url + "/details", HttpMethod.PATCH, new HttpEntity<>(patch), String.class).getBody()).contains("true");
        assertThat(http.getForObject(url + "?path=$.details", String.class)).contains("keep", "added").doesNotContain("remove");
    }

    @DatadamDocument(prefix = "demo:mutation:", timeToLive = 120)
    @DatadamIndex(name = "demo:mutation:number", unique = true, fields = @IndexField(path = "$.number", alias = "number", type = IndexField.Type.NUMERIC))
    @DatadamIndex(name = "demo:mutation:tags", unique = true, fields = @IndexField(path = "$.tags[*]", alias = "tag"))
    @DatadamIndex(name = "demo:mutation:code", unique = true, fields = @IndexField(path = "$.details.code", alias = "code"))
    @DatadamIndex(name = "demo:mutation:first", unique = true, fields = @IndexField(path = "$.slots[0]", alias = "first"))
    @DatadamIndex(name = "demo:mutation:search", kind = DatadamIndex.Kind.SEARCH, fields = {
        @IndexField(path = "$.number", alias = "number", type = IndexField.Type.NUMERIC),
        @IndexField(path = "$.tags[*]", alias = "tag", caseSensitive = true), @IndexField(path = "$.details.code", alias = "code", caseSensitive = true)})
    record Doc(@Id String id, Number number, List<String> tags, Map<String, Object> details) {}
}
