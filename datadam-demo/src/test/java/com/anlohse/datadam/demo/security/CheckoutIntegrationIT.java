package com.anlohse.datadam.demo.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.demo.DemoApplication;
import com.anlohse.datadam.demo.examples.inventory.CheckoutController.Request;
import com.anlohse.datadam.demo.examples.inventory.CheckoutService.Outcome;
import com.anlohse.datadam.demo.examples.inventory.StockController.ChangeQuantity;
import com.anlohse.datadam.demo.examples.inventory.StockItem;
import com.anlohse.datadam.demo.examples.inventory.StockRepository;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.spring.config.DatadamAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static com.anlohse.datadam.demo.examples.inventory.PurchaseOrder.Rejection.*;
import static com.anlohse.datadam.demo.examples.inventory.PurchaseOrder.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(45)
class CheckoutIntegrationIT {
    private static final UUID FAULT_STOCK_ID = UUID.randomUUID();
    @TempDir static Path directory;
    private static DisposableDatadam server;
    private static RespFaultProxy proxy;
    private static ConfigurableApplicationContext app;
    private static StockRepository stock;
    private static final TestRestTemplate http = new TestRestTemplate();
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        server = new DisposableDatadam(directory, """
            protected-mode yes
            user default off
            user setup on >test-setup-password ~demo:examples:* +@connection +idx.create +idx.info +json.set
            user checkout on >test-checkout-password ~demo:examples:* +@connection +eval +json.get +json.set
            """, false);
        var properties = server.properties();
        properties.setUsername("setup");
        properties.setPassword("test-setup-password");
        try (var pool = new DatadamAutoConfiguration().datadamPool(properties)) {
            var commands = new DatadamCommands(pool);
            new DatadamIndexOperations(commands).ensureIndexes(StockItem.class, Duration.ofSeconds(10));
            // Seed before attaching the proxy, so only the stock write during checkout loses its reply.
            commands.execute("JSON.SET", "demo:examples:stock:" + FAULT_STOCK_ID, "$",
                "{\"id\":\"" + FAULT_STOCK_ID + "\",\"sku\":\"lost-reply\",\"quantity\":10,\"version\":0}");
        }
        proxy = RespFaultProxy.forScriptKey(server.port, "demo:examples:stock:" + FAULT_STOCK_ID,
            RespFaultProxy.Fault.DROP_REPLY);
        app = new SpringApplicationBuilder(DemoApplication.class).run("--server.port=0", "--datadam.host=127.0.0.1",
            "--datadam.port=" + proxy.port(), "--datadam.username=checkout", "--datadam.password=test-checkout-password",
            "--datadam.ssl=false");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        stock = app.getBean(StockRepository.class);
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (app != null) app.close();
        } finally {
            try {
                if (proxy != null) proxy.close();
            } finally {
                if (server != null) server.close();
            }
        }
    }

    @Test
    void orderIdPreventsASecondDebitAndCannotBeReusedForDifferentInput() {
        UUID stockId = createStock();
        UUID orderId = UUID.randomUUID();
        var request = new Request(stockId, 3, 0L);
        var first = put(orderId, request);
        var repeated = put(orderId, request);
        var conflict = put(orderId, new Request(stockId, 2, 0L), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isEqualTo(new Outcome(orderId.toString(), COMPLETED, null));
        assertThat(repeated.getBody()).isEqualTo(first.getBody());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(orderId)).isEqualTo(first.getBody());
        assertThat(item(stockId).quantity()).isEqualTo(7);
        assertThat(item(stockId).version()).isEqualTo(1L);
    }

    @Test
    void staleVersionAndInsufficientStockLeaveOrdersWithKnownPartialOutcomes() {
        UUID stockId = createStock();
        UUID stale = UUID.randomUUID();
        UUID insufficient = UUID.randomUUID();
        assertThat(put(stale, new Request(stockId, 1, 1L)).getBody())
            .isEqualTo(new Outcome(stale.toString(), PARTIAL, STALE_STOCK_VERSION));
        assertThat(put(insufficient, new Request(stockId, 11, 0L)).getBody())
            .isEqualTo(new Outcome(insufficient.toString(), PARTIAL, INSUFFICIENT_STOCK));
        assertThat(status(stale).status()).isEqualTo(PARTIAL);
        assertThat(status(insufficient).status()).isEqualTo(PARTIAL);
        assertThat(item(stockId).quantity()).isEqualTo(10);
        assertThat(item(stockId).version()).isZero();
        UUID invalid = UUID.randomUUID();
        assertThat(put(invalid, new Request(stockId, 0, 0L), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity(base + "/examples/orders/" + invalid, String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void competingOrdersWithTheSameStockVersionDebitExactlyOnce() throws Exception {
        UUID stockId = createStock();
        var request = new Request(stockId, 3, 0L);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return put(UUID.randomUUID(), request).getBody(); });
            var second = executor.submit(() -> { start.await(); return put(UUID.randomUUID(), request).getBody(); });
            start.countDown();
            assertThat(new Outcome[] {first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)})
                .extracting(Outcome::status).containsExactlyInAnyOrder(COMPLETED, PARTIAL);
            assertThat(item(stockId).quantity()).isEqualTo(7);
            assertThat(item(stockId).version()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lostStockReplyReturnsUnknownAndReadOnlyReconciliationFindsTheReceipt() {
        UUID id = UUID.randomUUID();
        var request = new Request(FAULT_STOCK_ID, 2, 0L);
        var lost = put(id, request);
        assertThat(lost.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lost.getBody()).isEqualTo(new Outcome(id.toString(), UNKNOWN, null));
        assertThat(proxy.forwarded.get()).isEqualTo(1);
        // An ordinary stock edit preserves the receipt and changes the quantity again.
        http.put(base + "/examples/stock/" + FAULT_STOCK_ID, new ChangeQuantity(9, 1L));
        assertThat(status(id)).isEqualTo(new Outcome(id.toString(), COMPLETED, null));
        assertThat(put(id, request).getBody()).isEqualTo(status(id));
        assertThat(proxy.forwarded.get()).isEqualTo(2);
        assertThat(item(FAULT_STOCK_ID).quantity()).isEqualTo(9);
        assertThat(item(FAULT_STOCK_ID).version()).isEqualTo(2L);
    }

    private static UUID createStock() {
        UUID id = UUID.randomUUID();
        stock.save(new StockItem(id.toString(), id.toString(), 10, null));
        return id;
    }

    private static StockItem item(UUID id) {
        return stock.findById(id.toString()).orElseThrow();
    }

    private static ResponseEntity<Outcome> put(UUID id, Request request) {
        return put(id, request, Outcome.class);
    }

    private static <T> ResponseEntity<T> put(UUID id, Request request, Class<T> response) {
        return http.exchange(base + "/examples/orders/" + id, HttpMethod.PUT, new HttpEntity<>(request), response);
    }

    private static Outcome status(UUID id) {
        return http.getForObject(base + "/examples/orders/" + id, Outcome.class);
    }
}
