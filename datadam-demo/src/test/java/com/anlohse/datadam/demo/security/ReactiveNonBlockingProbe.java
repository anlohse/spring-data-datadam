package com.anlohse.datadam.demo.security;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.spring.ReactiveDatadamTemplate;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import com.anlohse.datadam.spring.repository.SimpleReactiveDatadamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Runs in an isolated JVM so its instrumentation cannot affect the imperative integration suite. */
public final class ReactiveNonBlockingProbe {
    public static void main(String[] args) throws Exception {
        BlockHound.builder().with(new reactor.blockhound.integration.ReactorIntegration())
            .markAsBlocking(Thread.class, "sleep", "(J)V")
            .markAsBlocking(com.anlohse.datadam.spring.DatadamCommands.class, "execute",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;")
            .nonBlockingThreadPredicate(existing -> existing.or(thread -> thread instanceof reactor.core.scheduler.NonBlocking
                || thread.getName().startsWith("lettuce-nioEventLoop")))
            .install();
        // Positive control proves that the detector is active on the scheduler used below.
        StepVerifier.create(Mono.fromRunnable(() -> {
            try { Thread.sleep(10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }).subscribeOn(Schedulers.parallel())).expectError(BlockingOperationError.class).verify(Duration.ofSeconds(10));
        StepVerifier.create(Mono.fromCallable(() -> new com.anlohse.datadam.spring.DatadamCommands(null).execute("PING"))
            .subscribeOn(Schedulers.parallel())).expectError(BlockingOperationError.class).verify(Duration.ofSeconds(10));
        UUID.randomUUID();
        var client = RedisClient.create();
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        var uri = RedisURI.Builder.redis("127.0.0.1", Integer.parseInt(args[0])).withTimeout(Duration.ofSeconds(5)).build();
        try (var connection = client.connectAsync(StringCodec.UTF8, uri).get(10, TimeUnit.SECONDS)) {
            var template = new ReactiveDatadamTemplate(new ReactiveDatadamOperations(connection),
                new ObjectMapper().findAndRegisterModules());
            var repository = new SimpleReactiveDatadamRepository<Sample, String>(template, Sample.class);
            StepVerifier.create(Flux.range(0, 32).flatMap(value -> repository.save(new Sample(null, value, null))
                    .flatMap(saved -> repository.findById(saved.id()).flatMap(repository::delete)), 8)
                .subscribeOn(Schedulers.parallel())).expectComplete().verify(Duration.ofSeconds(15));
            System.out.println("REACTIVE_NON_BLOCKING_OK");
        } finally {
            client.shutdown();
        }
    }

    @DatadamDocument(prefix = "reactive:non-blocking:", timeToLive = 30)
    public record Sample(@Id @DatadamGeneratedId String id, int value, @Version Long version) {}
}
