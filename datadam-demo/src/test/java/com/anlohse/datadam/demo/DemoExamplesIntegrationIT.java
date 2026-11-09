package com.anlohse.datadam.demo;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

import com.anlohse.datadam.demo.examples.ExampleAdministration;
import com.anlohse.datadam.demo.examples.ExampleScenarios;
import com.anlohse.datadam.demo.examples.ExampleSchemas;
import com.anlohse.datadam.demo.examples.catalog.Product;
import com.anlohse.datadam.demo.examples.catalog.ProductController;
import com.anlohse.datadam.demo.examples.inventory.StockController;
import com.anlohse.datadam.demo.examples.inventory.StockItem;
import com.anlohse.datadam.demo.examples.operations.ImportJob;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.EntityModel;
import com.anlohse.datadam.demo.examples.*;
import com.anlohse.datadam.demo.examples.catalog.*;
import com.anlohse.datadam.demo.examples.inventory.*;
import com.anlohse.datadam.demo.examples.operations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

/** Runs the same tutorials offered by the CLI, using real native indexes and application ACLs. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoExamplesIntegrationIT {
    @Autowired
    ExampleScenarios examples;
    @Autowired TestRestTemplate http;
    @Autowired
    DatadamCommands commands;
    private static String previousLocalCredentials;
    private final List<String> keys = new ArrayList<>();

    @BeforeAll static void schema() {
        previousLocalCredentials = System.getProperty("examples.local-credentials");
        System.setProperty("examples.local-credentials", "true");
        try (var pool = ExampleAdministration.pool("schema")) {
            ExampleSchemas.prepare(new DatadamIndexOperations(new DatadamCommands(pool)));
        }
    }
    @AfterAll static void restore() {
        if (previousLocalCredentials == null) System.clearProperty("examples.local-credentials");
        else System.setProperty("examples.local-credentials", previousLocalCredentials);
    }
    @AfterEach void cleanup() { keys.forEach(key -> commands.execute("DEL", key)); }
    static Stream<String> scenarios() { return ExampleScenarios.SCENARIOS.stream(); }
    @ParameterizedTest(name="Example {0}") @MethodSource("scenarios")
    void executableTutorial(String scenario) {
        var report = examples.run(scenario, 12);
        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.checks()).isNotEmpty();
    }
    @Test void httpCatalogCreatesQueriesProjectsAndRejectsDuplicateSku() {
        String token = UUID.randomUUID().toString();
        var input = new ProductController.CreateProduct(token, "Keyboard", token, BigDecimal.TEN, List.of("Java"));
        var created = http.postForEntity("/examples/products", input, Product.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Product product = Objects.requireNonNull(created.getBody()); track(Product.class, product.id());
        var bySku = http.getForObject("/examples/products/sku/" + token, Product.class);
        assertThat(bySku.id()).isEqualTo(product.id());
        assertThat(bySku.price()).isEqualByComparingTo(product.price());
        assertThat(http.getForObject("/examples/products/" + product.id(), Product.class).id()).isEqualTo(product.id());
        for (String route : List.of("", "/search", "/summaries")) {
            var result = http.getForEntity("/examples/products" + route + "?category=" + token + "&minimum=0&maximum=20&size=1", Map.class);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().get("content")).asList().hasSize(1);
        }
        var duplicate = http.postForEntity("/examples/products", input, Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().toString()).doesNotContain(token, "password", "JSON.SET");
        var invalid = http.postForEntity("/examples/products", new ProductController.CreateProduct("", "", token, BigDecimal.ONE, List.of()), Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.delete("/examples/products/" + product.id());
        assertThat(http.getForEntity("/examples/products/" + product.id(), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void httpStockCarriesExpectedVersionAndRejectsStaleEditAndDelete() {
        var created = http.postForEntity("/examples/stock", new StockController.CreateStock(UUID.randomUUID().toString(), 10), StockItem.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var original = Objects.requireNonNull(created.getBody()); track(StockItem.class, original.id());
        String path = "/examples/stock/" + original.id();
        var update = new StockController.ChangeQuantity(9, original.version());
        var first = http.exchange(path, HttpMethod.PUT, new HttpEntity<>(update), StockItem.class);
        assertThat(first.getBody().version()).isEqualTo(original.version() + 1);
        assertThat(http.exchange(path, HttpMethod.PUT, new HttpEntity<>(update), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(path + "?expectedVersion=" + original.version(), HttpMethod.DELETE, HttpEntity.EMPTY, Map.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(path + "?expectedVersion=" + first.getBody().version(), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
    }
    @Test void httpImportExposesKnownIdBeforeProcessingAndPollableProgress() {
        var created = http.postForEntity("/examples/imports", null, ImportJob.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var job = Objects.requireNonNull(created.getBody()); track(ImportJob.class, job.id());
        String path = "/examples/imports/" + job.id(); String sku = UUID.randomUUID().toString();
        try {
            assertThat(http.getForObject(path, ImportJob.class).status()).isEqualTo("PENDING");
            var product = new Product(null, sku, "Imported", sku, BigDecimal.ONE, List.of());
            var result = http.postForEntity(path + "/run", List.of(product, product), ImportJob.class);
            assertThat(result.getBody().status()).isEqualTo("PARTIAL");
            assertThat(result.getBody().processed()).isEqualTo(1);
            assertThat(result.getBody().rejected()).isEqualTo(1);
            assertThat(http.getForObject(path, ImportJob.class)).isEqualTo(result.getBody());
            assertThat(http.postForEntity(path + "/run", List.of(product), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            var current = http.getForObject(path, ImportJob.class);
            if (current != null) current.productIds().forEach(id -> track(Product.class, id));
        }
        assertThat(http.exchange(path, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity(path, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    private void track(Class<?> type, String id) { keys.add(new EntityModel<>(type).key(id)); }
}
