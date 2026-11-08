package io.datadam.demo;

import java.time.Duration;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.repository.DatadamQueryDiagnostics;
import org.springframework.data.domain.Pageable;
import redis.clients.jedis.*;

/** Explicit administrative diagnostics; never creates or migrates an index. */
public final class IndexDiagnosticsApplication {
    private IndexDiagnosticsApplication() {}
    public static void main(String[] args) throws NoSuchMethodException {
        String password = System.getenv("DATADAM_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set DATADAM_ADMIN_PASSWORD");
        String host = System.getenv().getOrDefault("DATADAM_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"));
        try (var pool = new JedisPool(new HostAndPort(host, port), DefaultJedisClientConfig.builder().user("schema").password(password).build())) {
            var commands = new DatadamCommands(pool);
            var indexes = new DatadamIndexOperations(commands);
            System.out.println("Index names: " + indexes.listIndexes());
            for (var type : List.of(Customer.class, HashCustomer.class)) {
                for (var comparison : indexes.compare(type)) {
                    System.out.println(comparison.index() + ": " + (comparison.compatible() ? "schema matches" : comparison.describe()));
                    if (comparison.exists()) {
                        System.out.println("  " + comparison.actual().status());
                        if (List.of(args).contains("--wait")) indexes.awaitReady(comparison.index(), Duration.ofSeconds(30));
                    }
                }
            }
            var diagnostics = new DatadamQueryDiagnostics(new DatadamTemplate(commands, new ObjectMapper()));
            var method = CustomerRepository.class.getMethod("findByEmailStartingWithOrderByEmailAsc", String.class, Pageable.class);
            System.out.println("Derived plan: " + diagnostics.explain(CustomerRepository.class, method));
        }
    }
}
