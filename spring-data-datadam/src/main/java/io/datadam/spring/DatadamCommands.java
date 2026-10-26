package io.datadam.spring;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.*;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.*;

/** RESP2 transport preserving nested arrays, integer replies and nil. One pooled connection per command. */
public class DatadamCommands {
    private final JedisPool pool;
    public DatadamCommands(JedisPool pool) { this.pool = pool; }
    public Object execute(String command, Object... args) {
        String[] values = Arrays.stream(args).map(v -> Objects.requireNonNull(v, "Command argument").toString()).toArray(String[]::new);
        try (var jedis = pool.getResource()) {
            return decode(jedis.sendCommand(() -> command.getBytes(StandardCharsets.US_ASCII), values));
        } catch (JedisDataException e) {
            if (e.getMessage().contains("UNIQUE index")) throw new DuplicateKeyException(e.getMessage(), e);
            if (e.getMessage().startsWith("NOPERM") || e.getMessage().startsWith("NOAUTH") || e.getMessage().startsWith("WRONGPASS"))
                throw new PermissionDeniedDataAccessException(e.getMessage(), e);
            throw new InvalidDataAccessResourceUsageException(e.getMessage(), e);
        } catch (JedisConnectionException e) {
            throw new DataAccessResourceFailureException("Datadam connection failed", e);
        }
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
