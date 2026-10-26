package io.datadam.spring;

import java.time.Duration;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.dao.*;

/** Explicit schema management; use an administrator connection, not application credentials. */
public class DatadamIndexOperations {
    private final DatadamCommands commands;
    public DatadamIndexOperations(DatadamCommands commands) { this.commands = commands; }
    public void ensureIndexes(Class<?> entity, Duration timeout) {
        var model = new EntityModel<>(entity);
        for (var index : entity.getAnnotationsByType(DatadamIndex.class)) {
            if (index.fields().length == 0 || index.fields().length > (index.kind() == DatadamIndex.Kind.COMPOUND ? 8 : 128))
                throw new IllegalArgumentException("Invalid number of index fields");
            if (index.unique() && index.kind() == DatadamIndex.Kind.SEARCH) throw new IllegalArgumentException("FT indexes cannot be UNIQUE");
            List<Object> args = new ArrayList<>(List.of(index.name(), "ON", "JSON", "PREFIX", 1, model.prefix(), "SCHEMA"));
            Set<String> aliases = new HashSet<>();
            for (var field : index.fields()) {
                if (field.path().isBlank() || field.alias().isBlank() || !aliases.add(field.alias())) throw new IllegalArgumentException("Invalid/duplicate index field");
                Collections.addAll(args, field.path(), "AS", field.alias(), field.type());
                if (field.caseSensitive()) {
                    if (index.kind() != DatadamIndex.Kind.SEARCH || field.type() != IndexField.Type.TAG)
                        throw new IllegalArgumentException("CASESENSITIVE applies only to FT TAG fields; IDX TAG is always case-sensitive");
                    args.add("CASESENSITIVE");
                }
            }
            if (index.unique()) args.add("UNIQUE");
            try {
                commands.execute(index.kind() == DatadamIndex.Kind.COMPOUND ? "IDX.CREATE" : "FT.CREATE", args.toArray());
            } catch (InvalidDataAccessResourceUsageException e) {
                if (!e.getMessage().equals("ERR Index already exists")) throw e;
            }
            awaitReady(index.name(), timeout);
            verify(model, index);
        }
    }
    public void awaitReady(String index, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            var info = DatadamCommands.map(commands.execute("IDX.INFO", index));
            if ("ready".equals(info.get("state"))) return;
            if ("failed".equals(info.get("state"))) throw new DataIntegrityViolationException("Index " + index + " failed: " + info.get("error"));
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DataAccessResourceFailureException("Interrupted waiting for index", e); }
        } while (System.nanoTime() < deadline);
        throw new QueryTimeoutException("Index " + index + " is still building");
    }
    private void verify(EntityModel<?> model, DatadamIndex index) {
        var info = DatadamCommands.map(commands.execute("IDX.INFO", index.name()));
        String kind = index.kind() == DatadamIndex.Kind.COMPOUND ? "compound" : "search";
        List<List<String>> fields = Arrays.stream(index.fields()).map(f -> List.of(f.alias(), f.type().name(), f.path())).toList();
        if (!kind.equals(info.get("kind")) || !"JSON".equals(info.get("on"))
            || !List.of(model.prefix()).equals(info.get("prefixes")) || !fields.equals(info.get("fields"))
            || ((Number) info.get("unique")).intValue() != (index.unique() ? 1 : 0))
            throw new DataIntegrityViolationException("Existing index schema differs from entity: " + index.name() + "; migrate explicitly");
        if (index.kind() == DatadamIndex.Kind.SEARCH) {
            var ft = DatadamCommands.map(commands.execute("FT.INFO", index.name()));
            var attributes = DatadamCommands.array(ft.get("attributes"));
            for (int i = 0; i < index.fields().length; i++) {
                boolean actual = DatadamCommands.array(attributes.get(i)).contains("CASESENSITIVE");
                if (actual != index.fields()[i].caseSensitive()) throw new DataIntegrityViolationException("TAG case mode differs: " + index.name());
            }
        }
    }
}
