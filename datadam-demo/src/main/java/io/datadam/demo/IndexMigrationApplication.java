package io.datadam.demo;

import java.time.Duration;
import java.util.Set;
import io.datadam.spring.*;
import redis.clients.jedis.*;

/** Separate administrative stages; application deployment/cutover is always external. */
public final class IndexMigrationApplication {
    private IndexMigrationApplication() {}
    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("inspect", "prepare", "validate", "retire", "discard-failed").contains(args[0]))
            throw new IllegalArgumentException("Specify exactly one action: inspect, prepare, validate, retire or discard-failed");
        String password = System.getenv("DATADAM_MIGRATION_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set DATADAM_MIGRATION_PASSWORD");
        String user = System.getenv().getOrDefault("DATADAM_MIGRATION_USER", "index-migrator");
        String host = System.getenv().getOrDefault("DATADAM_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"));
        var migration = new IndexMigration(CustomerSearchV2.class, "demo:customers:search", "demo:customers:search:v2");
        try (var pool = new JedisPool(new HostAndPort(host, port), DefaultJedisClientConfig.builder().user(user).password(password).build())) {
            var migrations = new DatadamIndexMigrations(new DatadamCommands(pool));
            switch (args[0]) {
                case "inspect" -> System.out.println(migrations.inspect(migration));
                case "prepare" -> System.out.println(migrations.prepare(migration, Duration.ofSeconds(30)));
                case "validate" -> System.out.println(migrations.validateReady(migration));
                case "retire" -> { migrations.retirePrevious(migration); System.out.println("Previous index retired; documents retained"); }
                case "discard-failed" -> { migrations.discardFailedTarget(migration); System.out.println("Failed target discarded; previous index retained"); }
            }
        }
    }
}
