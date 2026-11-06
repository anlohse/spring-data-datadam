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
            List<Object> args = new ArrayList<>(List.of(index.name(), "ON", model.storage(), "PREFIX", 1, model.prefix(), "SCHEMA"));
            Set<String> aliases = new HashSet<>();
            for (var field : index.fields()) {
                if (field.path().isBlank() || field.alias().isBlank() || !aliases.add(field.alias())) throw new IllegalArgumentException("Invalid/duplicate index field");
                if (model.isHash() && index.kind() == DatadamIndex.Kind.COMPOUND && !field.path().equals(field.alias()))
                    throw new IllegalArgumentException("IDX HASH does not support AS; alias must equal the HASH field name: " + field.path());
                if (!field.separator().isEmpty() && (field.type() != IndexField.Type.TAG || field.separator().length() != 1
                    || field.separator().charAt(0) == 0 || field.separator().charAt(0) > 127))
                    throw new IllegalArgumentException("SEPARATOR requires one non-NUL ASCII character on a TAG field");
                args.add(field.path());
                if (!model.isHash() || index.kind() == DatadamIndex.Kind.SEARCH) Collections.addAll(args, "AS", field.alias());
                args.add(field.type());
                if (!field.separator().isEmpty()) Collections.addAll(args, "SEPARATOR", field.separator());
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
        List<List<String>> fields = Arrays.stream(index.fields()).map(f -> {
            var expected = new ArrayList<String>(List.of(f.alias(), f.type().name()));
            if (!model.isHash() || index.kind() == DatadamIndex.Kind.SEARCH && !f.path().equals(f.alias())) expected.add(f.path());
            String separator = separator(model, index, f);
            if (!separator.isEmpty()) Collections.addAll(expected, "SEPARATOR", separator);
            return (List<String>) expected;
        }).toList();
        if (!kind.equals(info.get("kind")) || !model.storage().name().equals(info.get("on"))
            || !List.of(model.prefix()).equals(info.get("prefixes")) || !fields.equals(info.get("fields"))
            || ((Number) info.get("unique")).intValue() != (index.unique() ? 1 : 0))
            throw new DataIntegrityViolationException("Existing index schema differs from entity: " + index.name() + "; migrate explicitly");
        if (index.kind() == DatadamIndex.Kind.SEARCH) {
            var ft = DatadamCommands.map(commands.execute("FT.INFO", index.name()));
            var attributes = DatadamCommands.array(ft.get("attributes"));
            if (attributes.size() != index.fields().length) throw new DataIntegrityViolationException("FT attributes differ: " + index.name());
            for (int i = 0; i < index.fields().length; i++) {
                var attribute = attribute(DatadamCommands.array(attributes.get(i)));
                var field = index.fields()[i];
                if (!field.path().equals(attribute.get("identifier")) || !field.alias().equals(attribute.get("attribute"))
                    || !field.type().name().equals(attribute.get("type")) || attribute.containsKey("CASESENSITIVE") != field.caseSensitive()
                    || field.type() == IndexField.Type.TAG && !separator(model, index, field).equals(attribute.get("SEPARATOR")))
                    throw new DataIntegrityViolationException("FT attributes differ (path, alias, type, case or separator): " + index.name());
            }
        }
    }
    private String separator(EntityModel<?> model, DatadamIndex index, IndexField field) {
        return !field.separator().isEmpty() ? field.separator()
            : model.isHash() && index.kind() == DatadamIndex.Kind.SEARCH && field.type() == IndexField.Type.TAG ? "," : "";
    }
    private Map<String, Object> attribute(List<?> values) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < values.size();) {
            String name = values.get(i++).toString();
            if (name.equals("CASESENSITIVE")) result.put(name, true);
            else if (i < values.size()) result.put(name, values.get(i++));
            else throw new DataRetrievalFailureException("Malformed FT attribute");
        }
        return result;
    }
}
