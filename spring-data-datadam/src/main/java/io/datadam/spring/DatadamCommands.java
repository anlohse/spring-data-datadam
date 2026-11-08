package io.datadam.spring;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.*;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.*;
import io.datadam.spring.diagnostics.*;
import static io.datadam.spring.diagnostics.CommandObservation.Outcome.*;

/** RESP2 transport preserving nested arrays, integer replies and nil. One pooled connection per command. */
public class DatadamCommands {
    private final JedisPool pool;
    private final DatadamCommandObserver observer;
    public DatadamCommands(JedisPool pool) { this(pool, DatadamCommandObserver.NONE); }
    public DatadamCommands(JedisPool pool, DatadamCommandObserver observer) {
        this.pool = pool; this.observer = Objects.requireNonNull(observer);
    }
    public Object execute(String command, Object... args) {
        Objects.requireNonNull(command, "Command name");
        String[] values = Arrays.stream(args).map(v -> Objects.requireNonNull(v, "Command argument").toString()).toArray(String[]::new);
        long start = System.nanoTime(), acquisition = 0;
        boolean acquired = false;
        var outcome = CLIENT_ERROR;
        try {
            redis.clients.jedis.Jedis connection;
            try { connection = pool.getResource(); acquired = true; }
            catch (JedisException | IllegalStateException e) {
                outcome = acquisitionOutcome(e);
                if (outcome == PERMISSION_ERROR) throw new PermissionDeniedDataAccessException("Datadam connection authentication denied", e);
                throw new DataAccessResourceFailureException("Datadam connection acquisition failed", e);
            } finally { acquisition = System.nanoTime() - start; }
            try (var jedis = connection) {
                Object result = decode(jedis.sendCommand(() -> command.getBytes(StandardCharsets.US_ASCII), values));
                outcome = SUCCESS;
                return result;
            } catch (JedisDataException e) {
                String message = Objects.toString(e.getMessage(), "Datadam command rejected");
                if (message.contains("UNIQUE index")) { outcome = DUPLICATE_KEY; throw new DuplicateKeyException(message, e); }
                if (message.startsWith("NOPERM") || message.startsWith("NOAUTH") || message.startsWith("WRONGPASS")) {
                    outcome = PERMISSION_ERROR; throw new PermissionDeniedDataAccessException(message, e);
                }
                outcome = COMMAND_ERROR; throw new InvalidDataAccessResourceUsageException(message, e);
            } catch (JedisConnectionException e) {
                outcome = CONNECTION_ERROR; throw new DataAccessResourceFailureException("Datadam connection failed", e);
            }
        } catch (RuntimeException e) {
            if (outcome == SUCCESS) outcome = CLIENT_ERROR; // For example, failure while returning a resource.
            throw e;
        } finally {
            if (observer != DatadamCommandObserver.NONE) {
                try { observer.completed(new CommandObservation(CommandObservation.Operation.of(command), outcome,
                    System.nanoTime() - start, acquisition, acquired)); }
                catch (RuntimeException ignored) { /* Telemetry must not change command semantics or log sensitive failures. */ }
            }
        }
    }
    private CommandObservation.Outcome acquisitionOutcome(Throwable error) {
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            if (error instanceof JedisAccessControlException) return PERMISSION_ERROR;
            if (error instanceof JedisConnectionException) return CONNECTION_ERROR;
        }
        return POOL_ERROR;
    }
    private Object decode(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof List<?> list) return list.stream().map(this::decode).toList();
        return value;
    }
    public static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw new DataRetrievalFailureException("Expected RESP array, got " + value);
        return list;
    }
    public static Map<String, Object> map(Object value) {
        List<?> list = array(value);
        if (list.size() % 2 != 0) throw new DataRetrievalFailureException("Malformed RESP map");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i += 2) map.put(list.get(i).toString(), list.get(i + 1));
        return map;
    }
}
