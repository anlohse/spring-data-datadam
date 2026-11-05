package io.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import redis.clients.jedis.*;
import static org.assertj.core.api.Assertions.*;

/** Real Datadam integration. Explicit -Pintegration; never substituted by Redis or an in-memory fake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatadamIntegrationIT {
    @Autowired CustomerRepository repository;
    @Autowired DatadamTemplate template;
    @Autowired TestRestTemplate http;
    private final List<String> ownedIds = new ArrayList<>();
    private String city;
    static JedisPool pool(String username, String password) {
        return new JedisPool(new HostAndPort(System.getenv().getOrDefault("DATADAM_HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"))),
            DefaultJedisClientConfig.builder().user(username).password(password).build());
    }
    @BeforeAll static void schema() {
        try (var pool = pool("schema", System.getenv().getOrDefault("DATADAM_ADMIN_PASSWORD", "local-schema-password"))) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            indexes.ensureIndexes(Customer.class, Duration.ofSeconds(30));
            indexes.ensureIndexes(Customer.class, Duration.ofSeconds(30)); // idempotence + schema validation
        }
    }
    @BeforeEach void setup() { city = "city-" + UUID.randomUUID(); }
    @AfterEach void cleanup() { ownedIds.forEach(repository::deleteById); }
    Customer customer(String suffix, int age) {
        String id = "it-" + UUID.randomUUID() + suffix;
        ownedIds.add(id);
        return new Customer(id, "Ana " + suffix, id + "@example.test", city, age, List.of("java", "database"));
    }
    @Test void jsonCrudRoundTripsUnicodeAndNestedArray() {
        Customer c = customer("ação", 30);
        assertThat(repository.save(c)).isEqualTo(c);
        assertThat(repository.findById(c.id())).contains(c);
        assertThat(template.commands().execute("JSON.GET", "demo:customer:" + c.id(), "$.tags[0]")).isEqualTo("[\"java\"]");
        assertThat(repository.existsById(c.id())).isTrue();
        repository.delete(c);
        assertThat(repository.findById(c.id())).isEmpty();
    }
    @Test void compoundRangeOrderAndPageTotals() {
        var a = repository.save(customer("a", 19));
        var b = repository.save(customer("b", 25));
        repository.save(customer("c", 40));
        var page = repository.byCityAndAge(city, 18, 40, PageRequest.of(0, 1));
        assertThat(page.getContent()).containsExactly(a);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(repository.byCityAndAge(city, 18, 40, PageRequest.of(1, 1)).getContent()).containsExactly(b);
        assertThat(repository.byCityDescending(city, PageRequest.of(0, 10)).getContent()).extracting(Customer::age).containsExactly(40, 25, 19);
        assertThat(repository.count("demo:customers:city-age", CompoundQuery.all().where("city", "=", city))).isEqualTo(3);
    }
    @Test void uniqueLookupAndAtomicFailure() {
        var original = repository.save(customer("a", 30));
        var other = customer("b", 35);
        assertThatThrownBy(() -> repository.save(new Customer(other.id(), "conflict", original.email(), city, 35, List.of())))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.findById(other.id())).isEmpty();
        assertThat(repository.lookup("demo:customers:email", original.email())).containsExactly(original);
        assertThat(repository.lookup("demo:customers:email", "missing@example.test")).isEmpty();
        repository.delete(original);
        assertThat(repository.save(new Customer(other.id(), "reused", original.email(), city, 35, List.of())).email()).isEqualTo(original.email());
    }
    @Test void nativeJsonUpdateMovesIndexEntries() {
        var c = repository.save(customer("a", 19));
        template.commands().execute("JSON.NUMINCRBY", "demo:customer:" + c.id(), "$.age", 20);
        assertThat(repository.byCityAndAge(city, 18, 30, PageRequest.of(0, 10))).isEmpty();
        assertThat(repository.byCityAndAge(city, 30, 40, PageRequest.of(0, 10)).getContent()).extracting(Customer::age).containsExactly(39);
    }
    @Test void searchTagNumericOrNotAndSorting() {
        repository.save(customer("a", 19));
        repository.save(customer("b", 25));
        repository.save(customer("c", 40));
        var page = repository.searchCityAndAge(city.toUpperCase(Locale.ROOT), 18, 40, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "age")));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Customer::age).containsExactly(40, 25);
        var q = new SearchQuery("@city:{$city} (@age:[19 19] | @age:[40 40]) -@tag:{missing}", Map.of("city", city), Sort.by("age"));
        assertThat(repository.search("demo:customers:search", q, PageRequest.of(0, 10)).getContent()).extracting(Customer::age).containsExactly(19, 40);
    }
    @Test void boundTagAndIdxValuesCannotBecomeQueryOperators() {
        String special = "São Paulo | @age:[0 +inf] {x} *";
        var base = customer("special", 28);
        var c = repository.save(new Customer(base.id(), base.name(), base.email(), special, 28, base.tags()));
        assertThat(repository.byCityAndAge(special, 0, 100, PageRequest.of(0, 10)).getContent()).containsExactly(c);
        assertThat(repository.searchCityAndAge(special, 0, 100, PageRequest.of(0, 10)).getContent()).containsExactly(c);
    }
    @Test void ttlRemovesDocumentFromBothIndexFamilies() {
        var c = repository.save(customer("ttl", 22));
        template.commands().execute("EXPIRE", "demo:customer:" + c.id(), 0);
        assertThat(repository.findById(c.id())).isEmpty();
        assertThat(repository.count("demo:customers:city-age", CompoundQuery.all().where("city", "=", city))).isZero();
        assertThat(repository.searchCityAndAge(city, 0, 100, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }
    @Test void aclFiltersNativeResultsAndTotals() {
        var base = customer("visible", 30);
        String visibleId = "visible-" + base.id();
        ownedIds.add(visibleId);
        var visible = repository.save(new Customer(visibleId, base.name(), base.email(), city, 30, base.tags()));
        repository.save(customer("hidden", 31));
        try (var pool = pool("reader", "local-reader-password")) {
            var reader = new DatadamTemplate(new DatadamCommands(pool), new com.fasterxml.jackson.databind.ObjectMapper());
            var model = new EntityModel<>(Customer.class);
            var query = CompoundQuery.all().where("city", "=", city);
            assertThat(reader.query("demo:customers:city-age", query, PageRequest.of(0, 10), model).getContent()).containsExactly(visible);
            assertThat(reader.count("demo:customers:city-age", query, model)).isEqualTo(1);
            assertThat(reader.search("demo:customers:search", new SearchQuery("@city:{$c}", Map.of("c", city), Sort.unsorted()), PageRequest.of(0, 10), model).getTotalElements()).isEqualTo(1);
            assertThatThrownBy(() -> reader.findById(base.id(), model)).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }
    @Test void applicationCannotAdministerIndexesOrAccessOtherPrefixes() {
        assertThatThrownBy(() -> template.commands().execute("IDX.CREATE", "forbidden", "SCHEMA", "email", "TAG")).isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> template.commands().execute("JSON.GET", "other:secret")).isInstanceOf(PermissionDeniedDataAccessException.class);
    }
    @Test void concurrentUniqueClaimsHaveExactlyOneWinner() throws Exception {
        var a = customer("race-a", 30);
        var b = customer("race-b", 31);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Customer c : List.of(a, b)) futures.add(executor.submit(() -> {
                start.await();
                try { repository.save(new Customer(c.id(), c.name(), a.email(), city, c.age(), c.tags())); return true; }
                catch (DuplicateKeyException e) { return false; }
            }));
            start.countDown();
            int wins = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) wins++;
            assertThat(wins).isEqualTo(1);
            assertThat(repository.lookup("demo:customers:email", a.email())).hasSize(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void restEndpointsExposeQueriesAndUniqueConflict() {
        var c = customer("http", 30);
        assertThat(http.postForEntity("/customers", c, Customer.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/customers/" + c.id(), Customer.class)).isEqualTo(c);
        var duplicate = customer("duplicate", 31);
        var conflict = new Customer(duplicate.id(), duplicate.name(), c.email(), city, 31, List.of());
        assertThat(http.postForEntity("/customers", conflict, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity("/customers?city=" + city + "&minimum=18&maximum=40", String.class).getBody()).contains(c.id(), "totalElements");
        assertThat(http.getForEntity("/customers/search?city=" + city, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/customers/derived?city=" + city + "&minimum=18&maximum=30", String.class).getBody()).contains(c.id());
        assertThat(http.getForEntity("/customers?city=" + city + "&size=1000", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void schemaMismatchIsRejectedWithoutReplacingTheExistingIndex() {
        try (var pool = pool("schema", "local-schema-password")) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            assertThatThrownBy(() -> indexes.ensureIndexes(IncompatibleCustomer.class, Duration.ofSeconds(5)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("schema differs");
        }
    }
    @Test void derivedCompoundBetweenIsInclusiveAndRespectsPagesAndOrder() {
        repository.save(customer("lower", 18));
        repository.save(customer("middle", 25));
        repository.save(customer("upper", 40));
        repository.save(customer("outside", 41));
        var page = repository.findByCityAndAgeBetweenOrderByAgeDesc(city, 18, 40, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Customer::age).containsExactly(40, 25);
        assertThat(repository.findByCityAndAgeBetweenOrderByAgeDesc(city, 18, 40, PageRequest.of(1, 2)).getContent())
            .extracting(Customer::age).containsExactly(18);
        assertThat(repository.findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(city, 25))
            .extracting(Customer::age).containsExactly(41, 40);
    }
    @Test void derivedCountExistsAndUniqueLookupUseNativeCommands() {
        var c = repository.save(customer("email", 30));
        try (var pool = pool("app", "local-app-password")) {
            var commands = new RecordingCommands(pool);
            CustomerRepository derived = repository(commands);
            assertThat(derived.findByEmail(c.email())).contains(c);
            assertThat(commands.names).containsExactly("IDX.GET");
            commands.names.clear();
            assertThat(derived.existsByEmail(c.email())).isTrue();
            assertThat(derived.existsByEmail("absent-" + c.email())).isFalse();
            assertThat(commands.names).containsExactly("IDX.QUERY", "IDX.QUERY");
            commands.names.clear();
            assertThat(derived.countByCityAndAgeLessThan(city, 31)).isEqualTo(1);
            assertThat(commands.names).containsExactly("IDX.COUNT");
        }
        assertThat(repository.findByEmail("absent-" + c.email())).isEmpty();
    }
    @Test void derivedSliceFetchesOneExtraMatchWithoutCounting() {
        repository.save(customer("first", 20));
        repository.save(customer("second", 21));
        repository.save(customer("third", 22));
        try (var pool = pool("app", "local-app-password")) {
            var commands = new RecordingCommands(pool);
            var derived = repository(commands);
            var first = derived.findByCityAndAgeGreaterThanEqualOrderByAgeAsc(city, 20, PageRequest.of(0, 2));
            assertThat(first.getContent()).extracting(Customer::age).containsExactly(20, 21);
            assertThat(first.hasNext()).isTrue();
            var last = derived.findByCityAndAgeGreaterThanEqualOrderByAgeAsc(city, 20, PageRequest.of(1, 2));
            assertThat(last.getContent()).extracting(Customer::age).containsExactly(22);
            assertThat(last.hasNext()).isFalse();
            assertThat(commands.names).containsExactly("IDX.QUERY", "IDX.QUERY");
        }
    }
    @Test void derivedFindByCityPreservesMissingAgeAndExactTagCase() {
        var exact = repository.save(customer("exact", 30));
        var base = customer("folded", 31);
        repository.save(new Customer(base.id(), base.name(), base.email(), city.toUpperCase(Locale.ROOT), 31, base.tags()));
        var missing = customer("missing-age", 0);
        template.commands().execute("JSON.SET", "demo:customer:" + missing.id(), "$",
            "{\"id\":\"" + missing.id() + "\",\"name\":\"missing-age\",\"email\":\"" + missing.email() + "\",\"city\":\"" + city + "\",\"tags\":[]}");
        var results = repository.findByCityOrderByAgeAsc(city);
        assertThat(results).extracting(Customer::id).containsExactly(exact.id(), missing.id());
        assertThat(repository.findByCityIgnoreCaseOrderByAgeDesc(city)).extracting(Customer::id)
            .containsExactly(base.id(), exact.id(), missing.id());
    }
    @Test void derivedOrKeepsConjunctionGroupingAndExclusiveNumericBounds() {
        var yes = repository.save(customer("yes", 19));
        repository.save(customer("boundary", 20));
        var base = customer("email", 40);
        var byEmail = repository.save(new Customer(base.id(), base.name(), base.email(), "other-" + city, 40, base.tags()));
        var excluded = customer("excluded", 18);
        repository.save(new Customer(excluded.id(), excluded.name(), excluded.email(), "other-" + city, 18, excluded.tags()));
        var result = repository.findByCityAndAgeLessThanOrEmail(city, 20, byEmail.email(), PageRequest.of(0, 10, Sort.by("age")));
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).containsExactly(yes, byEmail);
    }
    @Test void derivedInBindsOperatorCharactersLiterally() {
        var normal = repository.save(customer("normal", 25));
        var base = customer("special", 19);
        String special = "x | @home:{*} \" \\ { }";
        var literal = repository.save(new Customer(base.id(), base.name(), base.email(), special, 19, base.tags()));
        var other = customer("other", 18);
        repository.save(new Customer(other.id(), other.name(), other.email(), "other-" + city, 18, other.tags()));
        assertThat(repository.findByCityInOrderByAgeAsc(List.of(city, special))).containsExactly(literal, normal);
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findByCityInOrderByAgeAsc(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findByCityIgnoreCaseOrderByAgeDesc("São Paulo"))
            .withMessageContaining("ASCII");
    }
    @Test void derivedDynamicSortMapsJavaPropertiesToNativeAliases() {
        repository.save(customer("high", 10000001));
        repository.save(customer("low", 10000000));
        var result = repository.findByAgeGreaterThanEqual(10000000, PageRequest.of(0, 10, Sort.by("age")));
        assertThat(result.getContent()).extracting(Customer::age).containsExactly(10000000, 10000001);
        // The native schema calls this field "home", whereas the repository accepts Java's "city".
        assertThat(repository.findByAgeGreaterThanEqual(10000000, PageRequest.of(0, 10, Sort.by("city"))).getTotalElements()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findByAgeGreaterThanEqual(1, PageRequest.of(0, 10, Sort.by("name"))))
            .withMessageContaining("No compatible native index");
    }
    @Test void derivedListHasNoImplicitHundredDocumentLimit() {
        for (int i = 0; i < 105; i++) repository.save(customer("list-" + i, i));
        assertThat(repository.findByCityOrderByAgeAsc(city)).hasSize(105);
        assertThat(repository.findByCityAndAgeGreaterThanEqual(city, 0)).hasSize(105);
    }
    @Test void derivedSingleResultDetectsDuplicatesAndFirstLimitsNatively() {
        var first = repository.save(customer("first", 20));
        assertThat(repository.findOneByCity(city)).contains(first);
        var last = repository.save(customer("last", 40));
        assertThatThrownBy(() -> repository.findOneByCity(city)).isInstanceOf(IncorrectResultSizeDataAccessException.class);
        assertThat(repository.findFirstByCityOrderByAgeDesc(city)).contains(last);
        assertThat(repository.findFirstByCityOrderByAgeDesc("missing-" + city)).isEmpty();
    }
    private CustomerRepository repository(DatadamCommands commands) {
        var factory = new io.datadam.spring.repository.DatadamRepositoryFactoryBean<CustomerRepository, Customer, String>(CustomerRepository.class);
        factory.setTemplate(new DatadamTemplate(commands, new com.fasterxml.jackson.databind.ObjectMapper()));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
    private static class RecordingCommands extends DatadamCommands {
        final List<String> names = new ArrayList<>();
        RecordingCommands(JedisPool pool) { super(pool); }
        @Override public Object execute(String command, Object... arguments) {
            names.add(command);
            return super.execute(command, arguments);
        }
    }
    @Test void duplicateExistingDocumentsFailUniqueBackfill() {
        repository.save(customer("existing-a", 20));
        repository.save(customer("existing-b", 21));
        try (var pool = pool("schema", "local-schema-password")) {
            var commands = new DatadamCommands(pool);
            try {
                assertThatThrownBy(() -> new DatadamIndexOperations(commands).ensureIndexes(DuplicateCity.class, Duration.ofSeconds(10)))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("failed");
            } finally { commands.execute("IDX.DROP", "demo:tests:unique-city"); }
        }
    }
    @DatadamDocument(prefix = "demo:customer:")
    @DatadamIndex(name = "demo:customers:city-age", fields = @IndexField(path = "$.city", alias = "city"))
    record IncompatibleCustomer(@Id String id) {}

    @DatadamDocument(prefix = "demo:customer:")
    @DatadamIndex(name = "demo:tests:unique-city", unique = true, fields = @IndexField(path = "$.city", alias = "city"))
    record DuplicateCity(@Id String id) {}
}
