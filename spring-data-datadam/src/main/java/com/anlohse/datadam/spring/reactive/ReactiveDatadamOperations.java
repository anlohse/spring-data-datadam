package com.anlohse.datadam.spring.reactive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.SearchQuery;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.output.ValueOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import reactor.core.publisher.Mono;

/**
 * Cold native operations over a caller-owned Lettuce connection using {@link StringCodec#UTF8}.
 * Each subscription with demand executes one command and buffers one bounded query page.
 * The caller owns connection creation and asynchronous shutdown.
 * Native writes bypass entity mapping, versioning, TTL policies, auditing, and callbacks.
 */
public final class ReactiveDatadamOperations {
    private final StatefulRedisConnection<String, String> connection;

    /**
     * Use RESP2, disable automatic reconnection, and reject commands while disconnected
     * before connecting the client. These settings prevent automatic replay of uncertain writes.
     * Use database zero and leave command auto-flushing enabled.
     */
    public ReactiveDatadamOperations(StatefulRedisConnection<String, String> connection) {
        this.connection = Objects.requireNonNull(connection, "Connection");
        var options = connection.getOptions();
        if (options.getConfiguredProtocolVersion() != ProtocolVersion.RESP2 || options.isAutoReconnect()
                || options.getDisconnectedBehavior() != ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) {
            throw new IllegalArgumentException("Use RESP2 with automatic reconnection disabled and disconnected commands rejected");
        }
    }

    /** Read the complete JSON text; a missing key completes without a value. */
    public Mono<String> jsonGet(String key) {
        return execute("JSON.GET", () -> new ValueOutput<>(StringCodec.UTF8), List.of(required(key)));
    }

    /** Read native JSON text at a path, preserving JSONPath match arrays and JSON null. */
    public Mono<String> jsonGet(String key, String path) {
        return execute("JSON.GET", () -> new ValueOutput<>(StringCodec.UTF8), List.of(required(key), required(path)));
    }

    /** Set serialized JSON. A path that cannot be created returns false. */
    public Mono<Boolean> jsonSet(String key, String path, String json) {
        return execute("JSON.SET", () -> new ValueOutput<>(StringCodec.UTF8),
            List.of(required(key), required(path), Objects.requireNonNull(json, "JSON text")))
            .map(reply -> {
                if (!reply.equals("OK")) throw new DataRetrievalFailureException("Expected JSON.SET acknowledgment");
                return true;
            })
            .defaultIfEmpty(false);
    }

    /** Delete the selected JSON value and return the native deletion count. */
    public Mono<Long> jsonDelete(String key, String path) {
        return execute("JSON.DEL", () -> new IntegerOutput<>(StringCodec.UTF8), List.of(required(key), required(path)));
    }

    /** Return an IDX page. Its order follows the index tuple and the query's descending flag. */
    public Mono<List<Row>> query(String index, CompoundQuery query, long offset, int limit) {
        return query(index, query, offset, limit, List.of());
    }

    /** Select JSON fields with RETURNJSON, or HASH fields with their native string representation. */
    public Mono<List<Row>> query(String index, CompoundQuery query, long offset, int limit, List<String> fields) {
        bounds(offset, limit);
        var args = Objects.requireNonNull(query, "Query").arguments(required(index));
        if (query.descending()) args.add("DESC");
        Collections.addAll(args, "LIMIT", offset, limit);
        projection(args, fields);
        return execute("IDX.QUERY", ArrayOutput::new, args).map(reply -> rows(reply, 0, limit));
    }

    /** Count the matching IDX entries as filtered by the server, including its ACL rules. */
    public Mono<Long> count(String index, CompoundQuery query) {
        return execute("IDX.COUNT", () -> new IntegerOutput<>(StringCodec.UTF8),
            Objects.requireNonNull(query, "Query").arguments(required(index)));
    }

    /** Return a bounded FT page and its total using one native command. */
    public Mono<SearchPage> search(String index, SearchQuery query, long offset, int limit) {
        return search(index, query, offset, limit, List.of());
    }

    /** Return an FT page with native field aliases and JSON types preserved by RETURNJSON. */
    public Mono<SearchPage> search(String index, SearchQuery query, long offset, int limit, List<String> fields) {
        bounds(offset, limit);
        Objects.requireNonNull(query, "Query");
        List<Object> args = new ArrayList<>(List.of(required(index), query.expression()));
        if (query.sort().isSorted()) {
            var order = query.sort().iterator().next();
            Collections.addAll(args, "SORTBY", order.getProperty(), order.getDirection());
        }
        Collections.addAll(args, "LIMIT", offset, limit);
        projection(args, fields);
        if (!query.parameters().isEmpty()) {
            Collections.addAll(args, "PARAMS", query.parameters().size() * 2);
            query.parameters().forEach((name, value) -> Collections.addAll(args, name, value));
            Collections.addAll(args, "DIALECT", 2);
        }
        return execute("FT.SEARCH", ArrayOutput::new, args).map(reply -> {
            if (reply.isEmpty() || !(reply.get(0) instanceof Long total) || total < 0) {
                throw new DataRetrievalFailureException("Missing or invalid FT total");
            }
            return new SearchPage(total, rows(reply, 1, limit));
        });
    }

    private <T> Mono<T> execute(String command, Supplier<CommandOutput<String, String, T>> output, List<?> arguments) {
        var values = arguments.stream().map(value -> Objects.requireNonNull(value, "Command argument").toString()).toList();
        return Mono.defer(() -> {
            ProtocolKeyword keyword = () -> command.getBytes(StandardCharsets.US_ASCII);
            var args = new CommandArgs<>(StringCodec.UTF8);
            values.forEach(args::add);
            return Mono.from(connection.reactive().<T>dispatch(keyword, output.get(), args));
        }).onErrorMap(RedisException.class, ReactiveDatadamOperations::translate);
    }

    /** Low-level scalar dispatch for native scripts and entity lifecycle commands. */
    public Mono<Object> executeScalar(String command, List<?> arguments) {
        return execute(required(command), ScalarOutput::new, arguments);
    }

    /** Low-level nested array dispatch, including SCAN and HASH field reads. */
    public Mono<List<Object>> executeArray(String command, List<?> arguments) {
        return execute(required(command), ArrayOutput::new, arguments);
    }

    private static final class ScalarOutput extends CommandOutput<String, String, Object> {
        private ScalarOutput() {
            super(StringCodec.UTF8, null);
        }

        @Override
        public void set(long number) {
            output = number;
        }

        @Override
        public void set(ByteBuffer bytes) {
            output = bytes == null ? null : StringCodec.UTF8.decodeValue(bytes);
        }
    }

    private static DataAccessException translate(RedisException failure) {
        if (!(failure instanceof RedisCommandExecutionException)) {
            return new DataAccessResourceFailureException("Datadam reactive command failed; reconcile uncertain writes", failure);
        }
        String message = Objects.toString(failure.getMessage(), "Datadam command rejected");
        if (message.contains("UNIQUE index")) return new DuplicateKeyException(message, failure);
        if (message.startsWith("NOPERM") || message.startsWith("NOAUTH") || message.startsWith("WRONGPASS")) {
            return new PermissionDeniedDataAccessException(message, failure);
        }
        return new InvalidDataAccessResourceUsageException(message, failure);
    }

    private static void projection(List<Object> args, List<String> fields) {
        var selectors = List.copyOf(Objects.requireNonNull(fields, "Fields"));
        selectors.forEach(ReactiveDatadamOperations::required);
        if (!selectors.isEmpty()) {
            Collections.addAll(args, "RETURNJSON", selectors.size());
            args.addAll(selectors);
        }
    }

    private static List<Row> rows(List<Object> reply, int offset, int limit) {
        int size = reply.size() - offset;
        if (size < 0 || size % 2 != 0 || size / 2 > limit) {
            throw new DataRetrievalFailureException("Malformed or oversized native query page");
        }
        var rows = new ArrayList<Row>();
        for (int i = offset; i < reply.size(); i += 2) {
            if (!(reply.get(i) instanceof String key)) throw new DataRetrievalFailureException("Expected document key");
            var fields = new LinkedHashMap<String, String>();
            DatadamCommands.map(reply.get(i + 1)).forEach((name, value) -> {
                if (!(value instanceof String text)) throw new DataRetrievalFailureException("Expected field text");
                fields.put(name, text);
            });
            rows.add(new Row(key, fields));
        }
        return List.copyOf(rows);
    }

    private static void bounds(long offset, int limit) {
        if (offset < 0 || limit <= 0) throw new IllegalArgumentException("A nonnegative offset and positive page limit are required");
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A nonblank key, index or path is required");
        return value;
    }

    /** Native fields. Full JSON documents use the field "$"; HASH fields contain stored strings. */
    public record Row(String key, Map<String, String> fields) {
        public Row {
            Objects.requireNonNull(key, "Key");
            fields = Map.copyOf(fields);
        }
    }

    /** A server-side total and a bounded list of native rows. */
    public record SearchPage(long total, List<Row> content) {
        public SearchPage {
            if (total < 0) throw new IllegalArgumentException("Total cannot be negative");
            content = List.copyOf(content);
        }
    }

    private static final class ArrayOutput extends NestedMultiOutput<String, String> {
        private boolean array;

        private ArrayOutput() {
            super(StringCodec.UTF8);
        }

        @Override
        public void multi(int count) {
            array = true;
            super.multi(count);
        }

        @Override
        public void set(ByteBuffer bytes) {
            if (bytes == null && !array) {
                throw new DataRetrievalFailureException("Expected native query array");
            }
            super.set(bytes);
        }
    }
}
