package example.datadam;

import java.time.Duration;

import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import redis.clients.jedis.*;

/** Separate process and credentials: the application user cannot create indexes. */
public final class SchemaApplication {
    private SchemaApplication() {}
    public static void main(String[] args) {
        String password = System.getenv("CONSUMER_SCHEMA_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set CONSUMER_SCHEMA_PASSWORD");
        String host = System.getenv().getOrDefault("DATADAM_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"));
        try (var pool = new JedisPool(new HostAndPort(host, port), DefaultJedisClientConfig.builder()
            .user("consumer-schema").password(password).build())) {
            new DatadamIndexOperations(new DatadamCommands(pool)).ensureIndexes(Book.class, Duration.ofSeconds(30));
            System.out.println("CONSUMER_SCHEMA_READY");
        }
    }
}
