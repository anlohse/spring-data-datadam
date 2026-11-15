package com.anlohse.datadam.demo;

import java.time.Duration;

import com.anlohse.datadam.demo.examples.ExampleSchemas;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import redis.clients.jedis.*;

/**
 * Run once with administrator credentials; the web application never needs index DDL rights.
 */
public final class SchemaApplication {
    private SchemaApplication() {
    }

    public static void main(String[] args) {
        String password = System.getenv("DATADAM_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set DATADAM_ADMIN_PASSWORD");
        String host = System.getenv().getOrDefault("DATADAM_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"));
        try (var pool = new JedisPool(new HostAndPort(host, port), DefaultJedisClientConfig.builder().user("schema").password(password).build())) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(Customer.class, Duration.ofSeconds(30));
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(TemporaryNote.class, Duration.ofSeconds(30));
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(AuditedNote.class, Duration.ofSeconds(30));
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(VersionedNote.class, Duration.ofSeconds(30));
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(HashCustomer.class, Duration.ofSeconds(30));
            ExampleSchemas.prepare(new DatadamIndexOperations(new DatadamCommands(pool)));
            System.out.println("Datadam schema ready: original demo and roadmap examples (JSON/HASH)");
        }
    }
}
