package com.anlohse.datadam.demo.security;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.anlohse.datadam.demo.reactive.*;
import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.*;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import com.anlohse.datadam.spring.repository.SimpleReactiveDatadamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(60)
class ReactiveRepositoriesIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    @TempDir static Path directory;
    private static DisposableDatadam server;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static SimpleReactiveDatadamRepository<TelemetryReading, String> repository;
    private static ReactiveDatadamTemplate template;
    private static ObjectMapper mapper;

    @BeforeAll
    static void start() throws Exception {
        server = DisposableDatadam.plain(directory);
        client = RedisClient.create();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        connection = client.connectAsync(StringCodec.UTF8, uri(server.port)).get(10, TimeUnit.SECONDS);
        mapper = new ObjectMapper().findAndRegisterModules();
        template = new ReactiveDatadamTemplate(new ReactiveDatadamOperations(connection), mapper,
            Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()),
            () -> Mono.deferContextual(context -> Mono.just(context.getOrDefault("actor", "test"))),
            Clock.fixed(NOW, ZoneOffset.UTC));
        repository = new SimpleReactiveDatadamRepository<>(template, TelemetryReading.class);
        try (var pool = server.pool()) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(TelemetryReading.class, TIMEOUT);
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(HashSample.class, TIMEOUT);
        }
    }

    @AfterAll
    static void stop() {
        try {
            if (client != null) client.shutdown();
        } finally {
            if (server != null) server.close();
        }
    }

    @Test
    void generatedIdVersionTtlAndContextAuditorFollowAcknowledgments() {
        var input = reading(UUID.randomUUID().toString(), 1);
        var flow = repository.save(input).flatMap(saved -> {
            assertThat(saved.id()).isNotBlank();
            assertThat(saved.version()).isZero();
            assertThat(saved.createdAt()).isEqualTo(NOW);
            assertThat(saved.createdBy()).isEqualTo("alice");
            assertThat(saved.modifiedBy()).isEqualTo("alice");
            return new ReactiveDatadamOperations(connection).executeScalar("PTTL", List.of(key(saved)))
                .doOnNext(value -> assertThat(((Number) value).longValue()).isBetween(1L, 600_000L))
                .then(repository.findById(saved.id())).flatMap(found -> {
                assertThat(found).isEqualTo(saved);
                return repository.save(change(saved, 42)).flatMap(updated -> {
                    assertThat(updated.version()).isEqualTo(1);
                    return repository.delete(saved).thenReturn(updated);
                });
            });
        });
        StepVerifier.create(flow.contextWrite(context -> context.put("actor", "alice")))
            .expectError(OptimisticLockingFailureException.class).verify(TIMEOUT);
        assertThat(input.id()).isNull();
        assertThat(input.version()).isNull();
    }

    @Test
    void projectionUsesNativeFieldsAndCompetingVersionsHaveOneWinner() {
        String device = UUID.randomUUID().toString();
        var flow = repository.save(reading(device, 1)).flatMap(saved ->
            Flux.merge(repository.save(change(saved, 10)).map(value -> "saved")
                    .onErrorReturn(OptimisticLockingFailureException.class, "conflict"),
                repository.save(change(saved, 11)).map(value -> "saved")
                    .onErrorReturn(OptimisticLockingFailureException.class, "conflict"))
                .collectList().doOnNext(results -> assertThat(results).containsExactlyInAnyOrder("saved", "conflict"))
                .then(repository.projectQuery("demo:examples:telemetry:device-sequence",
                    CompoundQuery.all().where("device", "=", device), PageRequest.of(0, 1), ProjectionSpec.of(Summary.class))));
        StepVerifier.create(flow).assertNext(page -> {
            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().get(0).device()).isEqualTo(device);
            assertThat(page.getContent().get(0).value()).isIn(10.0, 11.0);
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(repository.projectSearch("demo:examples:telemetry:search",
            new SearchQuery("@device:{$device}", Map.of("device", device), org.springframework.data.domain.Sort.unsorted()),
            PageRequest.of(0, 1), ProjectionSpec.of(Summary.class)))
            .assertNext(page -> assertThat(page.getTotalElements()).isEqualTo(1)).expectComplete().verify(TIMEOUT);
    }

    @Test
    void demandAndCancellationSaveOnlyTheRequestedEntities() {
        String device = UUID.randomUUID().toString();
        var sent = new AtomicInteger();
        var saves = repository.saveAll(Flux.range(0, 100).map(i -> reading(device, i)).doOnNext(value -> sent.incrementAndGet()));
        StepVerifier.create(saves, 0).expectSubscription().expectNoEvent(Duration.ofMillis(100))
            .thenRequest(1).assertNext(saved -> assertThat(saved.version()).isZero())
            .thenCancel().verify(TIMEOUT);
        assertThat(sent.get()).isEqualTo(1);
        StepVerifier.create(repository.count("demo:examples:telemetry:device-sequence",
            CompoundQuery.all().where("device", "=", device)))
            .expectNext(1L).expectComplete().verify(TIMEOUT);
    }

    @Test
    void mutableIdsAndVersionsStayUnchangedWhenACollisionOrLostReplyOccurs() throws Exception {
        var mutable = new MutableSample();
        mutable.id = UUID.randomUUID().toString();
        var mutableRepository = new SimpleReactiveDatadamRepository<MutableSample, String>(template, MutableSample.class);
        StepVerifier.create(mutableRepository.save(mutable)).expectNext(mutable).expectComplete().verify(TIMEOUT);
        var duplicate = new MutableSample();
        duplicate.id = mutable.id;
        StepVerifier.create(mutableRepository.save(duplicate)).expectError(OptimisticLockingFailureException.class).verify(TIMEOUT);
        assertThat(duplicate.version).isNull();
        try (var proxy = new RespFaultProxy(server.port, "EVAL", RespFaultProxy.Fault.DROP_REPLY);
             var socket = client.connectAsync(StringCodec.UTF8, uri(proxy.port())).get(10, TimeUnit.SECONDS)) {
            var faultRepository = new SimpleReactiveDatadamRepository<MutableSample, String>(
                new ReactiveDatadamTemplate(new ReactiveDatadamOperations(socket), mapper), MutableSample.class);
            var uncertain = new MutableSample();
            StepVerifier.create(faultRepository.save(uncertain))
                .expectError(org.springframework.dao.DataAccessResourceFailureException.class).verify(TIMEOUT);
            assertThat(uncertain.id).isNull();
            assertThat(uncertain.version).isNull();
            assertThat(proxy.forwarded.get()).isEqualTo(1);
        }
    }

    @Test
    void hashEntitiesShareVersionTtlAndProjectionRules() {
        var hashes = new SimpleReactiveDatadamRepository<HashSample, String>(template, HashSample.class);
        String name = UUID.randomUUID().toString();
        StepVerifier.create(hashes.save(new HashSample(null, name, null, null)).flatMap(saved ->
            hashes.findById(saved.id()).doOnNext(found -> assertThat(found).isEqualTo(saved))
                .then(hashes.projectQuery("reactive:hash-repository:name",
                    CompoundQuery.all().where("name", "=", name), PageRequest.of(0, 1), ProjectionSpec.of(NameOnly.class)))
                .doOnNext(page -> assertThat(page.getContent()).containsExactly(new NameOnly(name)))
                .then(hashes.persist(saved.id())).doOnNext(persistent -> assertThat(persistent).isTrue())
                .then(hashes.delete(saved))))
            .expectComplete().verify(TIMEOUT);
    }

    @Test
    void expirationRemovesTheEntityAndUniqueConflictsPreserveTheOriginal() {
        var hashes = new SimpleReactiveDatadamRepository<HashSample, String>(template, HashSample.class);
        String name = UUID.randomUUID().toString();
        StepVerifier.create(hashes.save(new HashSample(null, name, null, 200L)).flatMap(saved ->
            Mono.delay(Duration.ofMillis(500)).then(hashes.findById(saved.id()))))
            .expectComplete().verify(TIMEOUT);
        String device = UUID.randomUUID().toString();
        StepVerifier.create(repository.save(reading(device, 1)).flatMap(original ->
            repository.save(reading(device, 1)).onErrorResume(DuplicateKeyException.class, error ->
                repository.findById(original.id()).doOnNext(found -> assertThat(found).isEqualTo(original)))))
            .assertNext(found -> assertThat(found.version()).isZero()).expectComplete().verify(TIMEOUT);
    }

    @Test
    void projectionOnlyCredentialsCannotLoadOrWriteWholeEntities() throws Exception {
        String device = UUID.randomUUID().toString();
        TelemetryReading original = repository.save(reading(device, 1)).block(TIMEOUT);
        try (var pool = server.pool()) {
            new DatadamCommands(pool).execute("ACL", "SETUSER", "reactive-reader", "on", ">test-reader-password",
                "%R~demo:examples:telemetry:*", "+@connection", "+idx.query", "+idx.count", "+ft.search");
        }
        var uri = uri(server.port);
        uri.setUsername("reactive-reader");
        uri.setPassword("test-reader-password");
        try (var socket = client.connectAsync(StringCodec.UTF8, uri).get(10, TimeUnit.SECONDS)) {
            var reader = new SimpleReactiveDatadamRepository<TelemetryReading, String>(
                new ReactiveDatadamTemplate(new ReactiveDatadamOperations(socket), mapper), TelemetryReading.class);
            StepVerifier.create(reader.projectQuery("demo:examples:telemetry:device-sequence",
                CompoundQuery.all().where("device", "=", device), PageRequest.of(0, 1), ProjectionSpec.of(Summary.class)))
                .assertNext(page -> assertThat(page.getContent()).containsExactly(new Summary(device, 20.0)))
                .expectComplete().verify(TIMEOUT);
            StepVerifier.create(reader.findById(original.id()))
                .expectError(org.springframework.dao.PermissionDeniedDataAccessException.class).verify(TIMEOUT);
            StepVerifier.create(reader.save(reading(device, 2)))
                .expectError(org.springframework.dao.PermissionDeniedDataAccessException.class).verify(TIMEOUT);
        }
    }

    @Test
    void globalCrudMethodsEnumerateOnlyTheirPrefixAndRespectVersionedDeletes() {
        var samples = new SimpleReactiveDatadamRepository<MutableSample, String>(template, MutableSample.class);
        String id = UUID.randomUUID().toString();
        var sample = new MutableSample();
        sample.id = id;
        StepVerifier.create(samples.save(sample).then(samples.existsById(Mono.just(id))))
            .expectNext(true).expectComplete().verify(TIMEOUT);
        StepVerifier.create(samples.findAll()).assertNext(found -> assertThat(found.id).isNotBlank())
            .thenCancel().verify(TIMEOUT);
        StepVerifier.create(samples.deleteById(Mono.just(id)).then(samples.existsById(id)))
            .expectNext(false).expectComplete().verify(TIMEOUT);
    }

    @Test
    void springWebFluxRepositoryStreamsTelemetryOverHttpAndClosesItsSocket() throws Exception {
        ConfigurableApplicationContext app = ReactiveTelemetryApplication.start(uri(server.port),
            "--server.port=0", "--datadam.health.enabled=false", "--datadam.metrics.enabled=false").toCompletableFuture()
            .get(20, TimeUnit.SECONDS);
        var socket = app.getBean(StatefulRedisConnection.class);
        try {
            int port = ((ReactiveWebServerApplicationContext) app).getWebServer().getPort();
            var http = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(TIMEOUT).build();
            String device = UUID.randomUUID().toString();
            var saved = http.post().uri("/reactive/telemetry").contentType(org.springframework.http.MediaType.APPLICATION_NDJSON)
                .body(Flux.just(reading(device, 1), reading(device, 2)), TelemetryReading.class)
                .exchange().expectStatus().isOk().returnResult(TelemetryReading.class).getResponseBody();
            StepVerifier.create(saved).assertNext(value -> assertThat(value.id()).isNotBlank())
                .assertNext(value -> assertThat(value.createdBy()).isEqualTo("telemetry-demo")).expectComplete().verify(TIMEOUT);
            var response = http.get().uri("/reactive/telemetry?device=" + device + "&size=1")
                .exchange().expectStatus().isOk().returnResult(TelemetryReading.class).getResponseBody();
            StepVerifier.create(response, 1).assertNext(value -> assertThat(value.device()).isEqualTo(device))
                .thenCancel().verify(TIMEOUT);
            http.get().uri("/reactive/telemetry?device=" + device + "&size=0").exchange().expectStatus().isBadRequest();
            http.get().uri("/reactive/telemetry/missing").exchange().expectStatus().isNotFound();
        } finally {
            app.close();
        }
        await().atMost(TIMEOUT).until(() -> !socket.isOpen());
    }

    @Test
    void blockHoundDetectsBlockingControlAndAcceptsConcurrentReactiveCrud() throws Exception {
        String output = DisposableDatadam.run(Duration.ofSeconds(45),
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:+AllowRedefinitionToAddDeleteMethods", "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ReactiveNonBlockingProbe.class.getName(), Integer.toString(server.port));
        assertThat(output).contains("REACTIVE_NON_BLOCKING_OK");
    }

    private static RedisURI uri(int port) {
        return RedisURI.Builder.redis("127.0.0.1", port).withTimeout(TIMEOUT).build();
    }

    static TelemetryReading reading(String device, long sequence) {
        return new TelemetryReading(null, device, sequence, 20.0, null, null, null, null, null);
    }

    private static TelemetryReading change(TelemetryReading reading, double value) {
        return new TelemetryReading(reading.id(), reading.device(), reading.sequence(), value, reading.version(),
            reading.createdAt(), reading.modifiedAt(), reading.createdBy(), reading.modifiedBy());
    }

    private static String key(TelemetryReading reading) { return "demo:examples:telemetry:" + reading.id(); }
    public record Summary(String device, Double value) {}
    public record NameOnly(String name) {}

    @DatadamDocument(prefix = "reactive:hash-repository:", storage = DatadamStorage.HASH, timeToLive = 60)
    @DatadamIndex(name = "reactive:hash-repository:name", unique = true, fields = @IndexField(path = "name", alias = "name"))
    public record HashSample(@Id @DatadamGeneratedId String id, String name, @Version Long version,
                             @DatadamTimeToLive(unit = java.util.concurrent.TimeUnit.MILLISECONDS) Long ttl) {}

    @DatadamDocument(prefix = "reactive:mutable:")
    public static class MutableSample {
        @Id @DatadamGeneratedId public String id;
        @Version public Long version;
    }
}
