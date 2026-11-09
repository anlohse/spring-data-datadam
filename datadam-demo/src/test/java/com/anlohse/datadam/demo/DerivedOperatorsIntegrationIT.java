package com.anlohse.datadam.demo;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.DatadamTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DerivedOperatorsIntegrationIT {
    @Autowired CustomerRepository json;
    @Autowired HashCustomerRepository hash;
    @Autowired
    DatadamTemplate template;
    @Autowired TestRestTemplate http;
    final List<String> keys = new ArrayList<>();
    String scope;
    @BeforeAll static void schema() {
        try (var pool = DatadamIntegrationIT.pool("schema", "local-schema-password")) {
            var indexes = new DatadamIndexOperations(new DatadamCommands(pool));
            for (var type : List.of(Customer.class, HashCustomer.class)) indexes.ensureIndexes(type, Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup() { scope = "derived-" + UUID.randomUUID(); }
    @AfterEach void cleanup() { keys.forEach(key -> template.commands().execute("DEL", key)); }
    Customer customer(String email, String city, int age) {
        String id = UUID.randomUUID().toString(); keys.add("demo:customer:" + id);
        return json.save(new Customer(id, "Derived", email, city, age, List.of()));
    }
    HashCustomer hashCustomer(String email, Integer age) {
        String id = UUID.randomUUID().toString(); keys.add("demo:hash:customer:" + id);
        return hash.save(new HashCustomer(id, "Derived", email, scope, age, "", List.of(), Map.of()));
    }
    @Test void idxPrefixesPreserveLiteralCharactersAndExcludeSuffixMatchesInBothFormats() {
        for (String literal : List.of("*}|\\", " a ", "🚀", "\uD7FF", new String(Character.toChars(0x10ffff)))) {
            String prefix = literal + scope;
            var a = customer(prefix + "-yes", scope, 20); var b = hashCustomer(prefix + "-yes", 20);
            customer("other-" + prefix, scope, 20); hashCustomer("other-" + prefix, 20);
            assertThat(json.findByEmailStartingWithOrderByEmailAsc(prefix, PageRequest.of(0, 10)).getContent()).containsExactly(a);
            assertThat(hash.findByEmailStartingWithOrderByEmailAsc(prefix, PageRequest.of(0, 10)).getContent()).containsExactly(b);
        }
    }
    @Test void idxPrefixPaginationTotalsSliceTopCountAndExistsStayNative() {
        for (String suffix : List.of("c", "a", "b")) { customer(scope + suffix, scope, 20); hashCustomer(scope + suffix, 20); }
        var page = PageRequest.of(1, 1);
        var a = json.findByEmailStartingWithOrderByEmailAsc(scope, page);
        var b = hash.findByEmailStartingWithOrderByEmailAsc(scope, page);
        assertThat(a.getTotalElements()).isEqualTo(3); assertThat(b.getTotalElements()).isEqualTo(3);
        assertThat(a.getContent()).extracting(Customer::email).containsExactly(scope + "b");
        assertThat(b.getContent()).extracting(HashCustomer::email).containsExactly(scope + "b");
        assertThat(json.findByEmailStartingWith(scope, PageRequest.of(0, 2)).hasNext()).isTrue();
        assertThat(hash.findByEmailStartingWith(scope, PageRequest.of(1, 2)).hasNext()).isFalse();
        assertThat(json.findTop2ByEmailStartingWithOrderByEmailAsc(scope)).extracting(Customer::email).containsExactly(scope + "a", scope + "b");
        assertThat(hash.findTop2ByEmailStartingWithOrderByEmailAsc(scope)).extracting(HashCustomer::email).containsExactly(scope + "a", scope + "b");
        assertThat(json.countByEmailStartingWith(scope)).isEqualTo(3); assertThat(hash.countByEmailStartingWith(scope)).isEqualTo(3);
        assertThat(json.existsByEmailStartingWith(scope)).isTrue(); assertThat(hash.existsByEmailStartingWith(scope + "absent")).isFalse();
    }
    @Test void ftPrefixParametersKeepMetacharactersLiteralAndSortAndCountAfterNegation() {
        String prefix = scope + "*}|\\ ";
        customer(scope + "1", prefix + "young", 20); customer(scope + "2", prefix + "older", 30);
        customer(scope + "3", prefix + "excluded", 25); customer(scope + "4", scope, 40);
        var page = json.findByCityStartingWithAndAgeNotOrderByAgeAsc(prefix, 25, PageRequest.of(1, 1));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Customer::age).containsExactly(30);
    }
    @Test void foldedFtPrefixUsesAsciiCaseRules() {
        customer(scope + "1", scope.toUpperCase(Locale.ROOT) + "A", 30);
        customer(scope + "2", scope + "b", 20);
        assertThat(json.findByCityStartingWithIgnoreCaseOrderByAgeAsc(scope)).extracting(Customer::age).containsExactly(20, 30);
    }
    @Test void negationGroupsKeepAndOrPrecedenceAndNativeTotals() {
        var included = customer(scope + "1", scope + "a", 20);
        customer(scope + "2", scope + "b", 25); customer(scope + "3", scope + "c", 30);
        var alternative = customer(scope + "4", "outside", 25);
        assertThat(json.findByCityStartingWithAndAgeNotInOrEmail(scope, List.of(25, 30), alternative.email()))
            .containsExactlyInAnyOrder(included, alternative);
    }
    @Test void numericNotAndNotInExcludeMissingNullAndInvalidValuesInBothFormats() {
        var ja = customer(scope + "j20", scope, 20); var jb = customer(scope + "j30", scope, 30);
        var jmissing = customer(scope + "jm", scope, 40); var jnull = customer(scope + "jn", scope, 40); var jbad = customer(scope + "jb", scope, 40);
        template.commands().execute("JSON.DEL", "demo:customer:" + jmissing.id(), "$.age");
        template.commands().execute("JSON.SET", "demo:customer:" + jnull.id(), "$.age", "null");
        template.commands().execute("JSON.SET", "demo:customer:" + jbad.id(), "$.age", "\"invalid\"");
        var ha = hashCustomer(scope + "h20", 20); var hb = hashCustomer(scope + "h30", 30);
        var hm = hashCustomer(scope + "hm", null); var hbad = hashCustomer(scope + "hb", 40);
        template.commands().execute("HREPLACE", "demo:hash:customer:" + hbad.id(), "id", hbad.id(),
            "email", hbad.email(), "city", scope, "age", "invalid");
        var page = PageRequest.of(0, 10000);
        assertThat(json.findByAgeNot(20, page).getContent()).contains(jb).doesNotContain(ja)
            .extracting(Customer::id).doesNotContain(jmissing.id(), jnull.id(), jbad.id());
        assertThat(hash.findByAgeNot(20, page).getContent()).contains(hb).doesNotContain(ha)
            .extracting(HashCustomer::id).doesNotContain(hm.id(), hbad.id());
        assertThat(json.findByAgeNotIn(List.of(20, 30), page).getContent()).extracting(Customer::id)
            .doesNotContain(ja.id(), jb.id(), jmissing.id(), jnull.id(), jbad.id());
        assertThat(hash.findByAgeNotIn(List.of(20, 30), page).getContent()).extracting(HashCustomer::id)
            .doesNotContain(ha.id(), hb.id(), hm.id(), hbad.id());
    }
    @Test void hashPrefixTreatsCommaAsPartOfTheWholeField() {
        var value = hashCustomer(scope + ",tail", 20);
        assertThat(hash.findByEmailStartingWithOrderByEmailAsc(scope + ",", PageRequest.of(0, 10)).getContent()).containsExactly(value);
        assertThat(hash.findByEmailStartingWithOrderByEmailAsc("tail", PageRequest.of(0, 10)).getContent()).doesNotContain(value);
    }
    @Test void prefixReadsPreserveTtlAndExpiredRowsDisappear() {
        var a = customer(scope, scope, 20); var b = hashCustomer(scope, 20);
        for (String key : List.of("demo:customer:" + a.id(), "demo:hash:customer:" + b.id())) template.commands().execute("PEXPIRE", key, 120000);
        var expiry = keys.stream().map(k -> template.commands().execute("PEXPIRETIME", k)).toList();
        assertThat(json.countByEmailStartingWith(scope)).isEqualTo(1); assertThat(hash.countByEmailStartingWith(scope)).isEqualTo(1);
        assertThat(keys.stream().map(k -> template.commands().execute("PEXPIRETIME", k)).toList()).isEqualTo(expiry);
        keys.forEach(k -> template.commands().execute("EXPIRE", k, 0));
        assertThat(json.countByEmailStartingWith(scope)).isZero(); assertThat(hash.countByEmailStartingWith(scope)).isZero();
    }
    @Test void demoHttpRoutesExposeBothStorageFormats() {
        customer(scope, scope, 20); hashCustomer(scope, 20);
        for (String format : List.of("json", "hash")) {
            var response = http.getForEntity("/customer-queries/" + format + "/prefix?prefix=" + scope, String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); assertThat(response.getBody()).contains(scope, "\"totalElements\":1");
            var negative = http.getForEntity("/customer-queries/" + format + "/age-not-in?excluded=20,30", String.class);
            assertThat(negative.getStatusCode().is2xxSuccessful()).isTrue(); assertThat(negative.getBody()).doesNotContain(scope);
        }
    }
}
