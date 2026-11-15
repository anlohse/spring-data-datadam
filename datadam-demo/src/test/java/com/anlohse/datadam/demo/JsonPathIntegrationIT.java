package com.anlohse.datadam.demo;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.callback.BeforeSaveCallback;
import com.anlohse.datadam.spring.callback.DatadamSaveContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JsonPathIntegrationIT.Fixtures.class)
class JsonPathIntegrationIT {
    @Autowired
    DatadamTemplate template;
    @Autowired CustomerRepository customers;
    @Autowired TemporaryNoteRepository notes;
    @Autowired VersionedNoteRepository versioned;
    @Autowired TestRestTemplate http;
    private final List<String> ownedKeys = new ArrayList<>();
    private DatadamJsonOperations<Customer> json;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            for (var type : List.of(Customer.class, TemporaryNote.class, VersionedNote.class)) indexes.ensureIndexes(type, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { json = template.json(Customer.class); }
    @AfterEach void cleanup() { ownedKeys.forEach(k -> template.commands().execute("DEL", k)); }
    private Customer customer() {
        String id = UUID.randomUUID().toString(); ownedKeys.add("demo:customer:" + id);
        return customers.save(new Customer(id, "Ana", id + "@example.test", "city-" + id, 32, List.of("java", "database")));
    }
    private long deadline(String key) { return ((Number) template.commands().execute("PEXPIRETIME", key)).longValue(); }
    private TemporaryNote note() {
        var saved = notes.save(new TemporaryNote(null, UUID.randomUUID().toString(), "json-it-" + UUID.randomUUID(), "original", 120000L));
        ownedKeys.add("demo:temporary:" + saved.id()); return saved;
    }

    @Test void jsonpathAndLegacyReadTheSameArrayWithoutDroppingItsNesting() {
        var saved = customer();
        var expected = new JsonReadResult<>(true, List.of(List.of("java", "database")));
        assertThat(json.read(saved.id(), JsonPath.of("$.tags", new TypeReference<List<String>>() {}))).isEqualTo(expected);
        assertThat(json.read(saved.id(), JsonPath.of(".tags", new TypeReference<List<String>>() {}))).isEqualTo(expected);
        assertThat(json.read(saved.id(), JsonPath.of("$.tags[*]", String.class)).values()).containsExactly("java", "database");
        assertThat(json.read(saved.id(), JsonPath.of("$.tags[?(@ == 'java')]", String.class)).values()).containsExactly("java");
        assertThat(json.read(saved.id(), JsonPath.of("$", Customer.class)).values()).containsExactly(saved);
        assertThat(json.read(saved.id(), JsonPath.of(".", Customer.class)).values()).containsExactly(saved);
    }
    @Test void distinguishesMissingKeyMissingPathAndExplicitNull() {
        var saved = customer(); var path = JsonPath.of("$.name", String.class);
        assertThat(json.read("missing-" + UUID.randomUUID(), path)).isEqualTo(new JsonReadResult<String>(false, List.of()));
        assertThat(json.read(saved.id(), JsonPath.of("$.unknown", String.class))).isEqualTo(new JsonReadResult<String>(true, List.of()));
        assertThatThrownBy(() -> json.read(saved.id(), JsonPath.of(".unknown", String.class))).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(json.set(saved.id(), path, null)).isTrue();
        assertThat(json.read(saved.id(), path).values()).containsExactly((String) null);
        assertThat(json.read(saved.id(), JsonPath.of(".name", String.class)).values()).containsExactly((String) null);
        assertThat(json.remove(saved.id(), path)).isEqualTo(1);
        assertThat(json.read(saved.id(), path).values()).isEmpty();
        assertThat(json.remove(saved.id(), path)).isZero();
    }
    @Test void absentParentsScalarParentsAndMissingKeysFollowNativeSetRules() {
        var saved = customer();
        assertThat(json.set(saved.id(), JsonPath.of("$.extra", String.class), "created")).isTrue();
        assertThat(json.read(saved.id(), JsonPath.of(".extra", String.class)).values()).containsExactly("created");
        assertThat(json.set(saved.id(), JsonPath.of("$.absent.child", String.class), "x")).isFalse();
        assertThat(json.set(saved.id(), JsonPath.of("$.age.child", String.class), "x")).isFalse();
        assertThat(json.set(saved.id(), JsonPath.of("$.tags[90]", String.class), "x")).isFalse();
        String missing = UUID.randomUUID().toString();
        assertThatThrownBy(() -> json.set(missing, JsonPath.of("$.name", String.class), "x")).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(json.remove(missing, JsonPath.of("$.name", String.class))).isZero();
        assertThat(customers.findById(missing)).isEmpty();
    }
    @Test void incompatibleTypesAndInvalidPathsDoNotMasqueradeAsEmptyResults() {
        var saved = customer();
        assertThatThrownBy(() -> json.read(saved.id(), JsonPath.of("$.name", Integer.class))).isInstanceOf(DataRetrievalFailureException.class);
        assertThatThrownBy(() -> json.read(saved.id(), JsonPath.of("$.age", String.class))).isInstanceOf(DataRetrievalFailureException.class);
        assertThatThrownBy(() -> json.read(saved.id(), JsonPath.of("$.tags", String.class))).isInstanceOf(DataRetrievalFailureException.class);
        assertThatThrownBy(() -> json.read(saved.id(), JsonPath.of("$[", String.class))).isInstanceOf(InvalidDataAccessResourceUsageException.class);
    }
    @Test void changingIndexedFieldsUpdatesIdxAndFtAndPreservesExactExpiry() {
        var saved = customer(); String key = "demo:customer:" + saved.id();
        template.commands().execute("PEXPIRE", key, 120000); long expiry = deadline(key);
        String city = "new-" + UUID.randomUUID();
        assertThat(json.set(saved.id(), JsonPath.of("$.city", String.class), city)).isTrue();
        assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(customers.byCityAndAge(saved.city(), 0, 100, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(customers.byCityAndAge(city, 0, 100, PageRequest.of(0, 10)).getContent()).extracting(Customer::id).containsExactly(saved.id());
        assertThat(customers.searchCityAndAge(saved.city(), 0, 100, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(customers.searchCityAndAge(city, 0, 100, PageRequest.of(0, 10)).getContent()).extracting(Customer::id).containsExactly(saved.id());
        assertThat(json.remove(saved.id(), JsonPath.of(".city", String.class))).isEqualTo(1);
        assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(customers.byCityAndAge(city, 0, 100, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(customers.searchCityAndAge(city, 0, 100, PageRequest.of(0, 10)).getContent()).isEmpty();
    }
    @Test void uniqueRefusalPreservesDocumentDeadlineAndBothOwners() {
        var owner = customer(); var other = customer(); String key = "demo:customer:" + other.id();
        template.commands().execute("PEXPIRE", key, 120000); long expiry = deadline(key);
        Object before = template.commands().execute("JSON.GET", key);
        assertThatThrownBy(() -> json.set(other.id(), JsonPath.of("$.email", String.class), owner.email())).isInstanceOf(DuplicateKeyException.class);
        assertThat(template.commands().execute("JSON.GET", key)).isEqualTo(before); assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(customers.findByEmail(owner.email())).contains(owner); assertThat(customers.findByEmail(other.email())).contains(other);
    }
    @Test void defaultAndPerEntityTtlArePreservedRatherThanResetByPartialWrites() {
        var saved = note(); String key = "demo:temporary:" + saved.id(); long expiry = deadline(key);
        var ops = template.json(TemporaryNote.class);
        assertThat(ops.set(saved.id(), JsonPath.of("$.text", String.class), "partial")).isTrue();
        assertThat(ops.remove(saved.id(), JsonPath.of("$.text", String.class))).isEqualTo(1);
        assertThat(deadline(key)).isEqualTo(expiry);
        assertThatThrownBy(() -> ops.set(saved.id(), JsonPath.of("$.ttlMillis", Long.class), 300000L)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(notes.findById(saved.id()).orElseThrow().ttlMillis()).isEqualTo(120000L);
    }
    @Test void mutationGuardsPreserveVersionAuditAndIdentity() {
        var saved = versioned.save(new VersionedNote(null, UUID.randomUUID().toString(), "json-it", "original", null, null, null, null, null));
        String key = "demo:versioned:" + saved.id(); ownedKeys.add(key);
        Object before = template.commands().execute("JSON.GET", key); long expiry = deadline(key);
        var managed = template.json(VersionedNote.class);
        assertThat(managed.read(saved.id(), JsonPath.of("$.version", Long.class)).values()).containsExactly(0L);
        assertThatThrownBy(() -> managed.set(saved.id(), JsonPath.of("$.text", String.class), "bypass")).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> managed.remove(saved.id(), JsonPath.of("$.text", String.class))).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(template.commands().execute("JSON.GET", key)).isEqualTo(before); assertThat(deadline(key)).isEqualTo(expiry);
        assertThatThrownBy(() -> template.json(AuditedNote.class).set("any", JsonPath.of("$.text", String.class), "bypass")).isInstanceOf(InvalidDataAccessApiUsageException.class);
        var customer = customer();
        assertThatThrownBy(() -> json.set(customer.id(), JsonPath.of("$['id']", String.class), "new-id")).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> json.remove(customer.id(), JsonPath.of("$", Object.class))).isInstanceOf(IllegalArgumentException.class);
        assertThat(customers.findById(customer.id())).contains(customer);
    }
    @Test void matchingWriteCallbackIsGuardedButUnrelatedCallbacksDoNotBlockCustomers() {
        assertThatThrownBy(() -> template.json(CallbackNote.class).set("any", JsonPath.of("$.text", String.class), "bypass"))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("callbacks");
        var saved = customer(); assertThat(json.set(saved.id(), JsonPath.of("$.name", String.class), "allowed")).isTrue();
    }
    @Test void expiredDocumentCannotBeRecreatedByPartialWrites() {
        var saved = note(); template.commands().execute("EXPIRE", "demo:temporary:" + saved.id(), 0);
        var ops = template.json(TemporaryNote.class);
        assertThatThrownBy(() -> ops.set(saved.id(), JsonPath.of("$.text", String.class), "resurrect")).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(ops.remove(saved.id(), JsonPath.of("$.text", String.class))).isZero();
        assertThat(ops.read(saved.id(), JsonPath.of("$.text", String.class)).keyExists()).isFalse();
        assertThat(notes.findByCategory(saved.category())).isEmpty();
    }
    @Test void aclStillControlsEachNativeOperation() {
        var saved = note(); String key = "demo:temporary:" + saved.id(); long expiry = deadline(key);
        try (var pool = DatadamIntegrationIT.pool("ttl-restricted", "local-ttl-restricted-password")) {
            var restricted = new DatadamTemplate(new DatadamCommands(pool), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()).json(TemporaryNote.class);
            // No PEXPIRE permission is needed: partial changes preserve the existing expiration.
            assertThat(restricted.set(saved.id(), JsonPath.of("$.text", String.class), "permitted")).isTrue();
            assertThatThrownBy(() -> restricted.remove(saved.id(), JsonPath.of("$.text", String.class))).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> new DatadamTemplate(new DatadamCommands(pool), new com.fasterxml.jackson.databind.ObjectMapper())
                .json(Customer.class).read("forbidden", JsonPath.of("$.name", String.class))).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
        assertThat(deadline(key)).isEqualTo(expiry);
        assertThat(notes.findById(saved.id()).orElseThrow().text()).isEqualTo("permitted");
    }
    @Test void httpExampleReadsSetsAndRemovesValues() {
        var saved = customer(); String url = "/customers/" + saved.id() + "/json";
        assertThat(http.getForObject(url + "?path=$.tags", String.class)).contains("keyExists", "java", "database");
        assertThat(http.exchange(url + "/city", HttpMethod.PUT, new HttpEntity<>(new CustomerJsonController.CityInput("updated")), String.class).getBody()).contains("\"written\":true");
        assertThat(customers.findById(saved.id()).orElseThrow().city()).isEqualTo("updated");
        assertThat(http.exchange(url + "/name", HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getBody()).contains("\"removed\":1");
        assertThat(customers.findById(saved.id()).orElseThrow().name()).isNull();
        assertThat(http.getForEntity(url + "?path=$[", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DatadamDocument(prefix = "demo:temporary:") record CallbackNote(@Id String id, String text) {}
    static class ValidateCallback implements BeforeSaveCallback<CallbackNote> {
        public CallbackNote onBeforeSave(CallbackNote note, DatadamSaveContext context) { return note; }
    }
    @TestConfiguration static class Fixtures { @Bean ValidateCallback partialWriteGuardCallback() { return new ValidateCallback(); } }
}
