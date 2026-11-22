package com.anlohse.datadam.demo.reactive;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.anlohse.datadam.spring.ReactiveDatadamTemplate;
import com.anlohse.datadam.spring.UuidDatadamIdGenerator;
import com.anlohse.datadam.spring.config.EnableReactiveDatadamRepositories;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** Standalone WebFlux example. Connection establishment finishes before Spring starts on a worker thread. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableReactiveDatadamRepositories(basePackageClasses = TelemetryRepository.class)
@Profile("reactive-telemetry")
public class ReactiveTelemetryApplication {
    public static void main(String[] args) {
        var uri = RedisURI.Builder.redis(System.getenv().getOrDefault("DATADAM_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380")))
            .withAuthentication(System.getenv().getOrDefault("DATADAM_USERNAME", "app"),
                System.getenv().getOrDefault("DATADAM_PASSWORD", "local-app-password"))
            .withTimeout(Duration.ofSeconds(5)).build();
        // The main thread keeps the JVM alive until Spring starts its server.
        start(uri, args).toCompletableFuture().join();
    }

    public static CompletionStage<ConfigurableApplicationContext> start(RedisURI uri, String... args) {
        var client = RedisClient.create();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        return client.connectAsync(StringCodec.UTF8, uri).thenApplyAsync(connection -> {
            var startupThread = Thread.currentThread();
            var previousLoader = startupThread.getContextClassLoader();
            try {
                startupThread.setContextClassLoader(ReactiveTelemetryApplication.class.getClassLoader());
                return new SpringApplicationBuilder(ReactiveTelemetryApplication.class)
                    .web(WebApplicationType.REACTIVE).profiles("reactive-telemetry")
                    .initializers(context -> {
                        context.getBeanFactory().registerSingleton("telemetryConnection", connection);
                        context.addApplicationListener(event -> {
                            if (event instanceof ContextClosedEvent)
                                connection.closeAsync().thenCompose(unused -> client.shutdownAsync());
                        });
                    }).run(args);
            } catch (RuntimeException failure) {
                connection.closeAsync();
                throw failure;
            } finally {
                startupThread.setContextClassLoader(previousLoader);
            }
        }).whenComplete((context, failure) -> {
            if (failure != null) client.shutdownAsync();
        });
    }

    @Bean
    NettyReactiveWebServerFactory telemetryWebServer() {
        return new NettyReactiveWebServerFactory();
    }

    @Bean
    ReactiveDatadamTemplate reactiveDatadamTemplate(StatefulRedisConnection<String, String> connection, ObjectMapper mapper) {
        return new ReactiveDatadamTemplate(new ReactiveDatadamOperations(connection), mapper,
            Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()),
            () -> Mono.deferContextual(context -> Mono.just(context.getOrDefault("actor", "telemetry-demo"))),
            Clock.systemUTC());
    }

    @Bean
    RouterFunction<ServerResponse> telemetryRoutes(TelemetryRepository repository) {
        return RouterFunctions.route()
            .POST("/reactive/telemetry", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_NDJSON)
                .body(request.bodyToFlux(TelemetryReading.class).doOnNext(reading -> {
                    if (reading.device() == null || reading.device().isBlank() || reading.sequence() == null
                            || reading.sequence() < 0 || reading.value() == null || !Double.isFinite(reading.value()))
                        throw new IllegalArgumentException("Device, nonnegative sequence and finite value are required");
                }).concatMap(repository::save, 0), TelemetryReading.class))
            .GET("/reactive/telemetry/{id}", request -> repository.findById(request.pathVariable("id"))
                .flatMap(value -> ServerResponse.ok().bodyValue(value)).switchIfEmpty(ServerResponse.notFound().build()))
            .GET("/reactive/telemetry", request -> Mono.defer(() -> {
                int page = Integer.parseInt(request.queryParam("page").orElse("0"));
                int size = Integer.parseInt(request.queryParam("size").orElse("20"));
                if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
                return repository.byDevice(request.queryParam("device").orElseThrow(() -> new IllegalArgumentException("Device is required")), page, size)
                    .flatMap(result -> ServerResponse.ok().contentType(MediaType.APPLICATION_NDJSON)
                        .body(Flux.fromIterable(result.getContent()), TelemetryReading.class));
            }))
            .onError(IllegalArgumentException.class, (error, request) -> ServerResponse.badRequest().build())
            .onError(DuplicateKeyException.class, (error, request) -> ServerResponse.status(HttpStatus.CONFLICT).build())
            .onError(OptimisticLockingFailureException.class, (error, request) -> ServerResponse.status(HttpStatus.CONFLICT).build())
            .onError(DataAccessException.class, (error, request) -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build())
            .build();
    }
}
