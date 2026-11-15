package com.anlohse.datadam.spring;

import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.dao.*;

/** Explicit schema management and read-only inspection; permissions follow the commands used. */
public class DatadamIndexOperations {
    private final DatadamCommands commands;
    public DatadamIndexOperations(DatadamCommands commands) { this.commands = commands; }
    /** Lists all IDX and FT names. Names/schema metadata are not filtered by document key ACLs. */
    public List<String> listIndexes() {
        return DatadamCommands.array(commands.execute("IDX.LIST")).stream().map(Object::toString).toList();
    }
    /** Poll this lightweight snapshot to follow backfill without FT.INFO's entry scan. */
    public IndexStatus status(String index) {
        return status(index, DatadamCommands.map(commands.execute("IDX.INFO", index)));
    }
    /** Reads IDX.INFO and, for search indexes, FT.INFO for case metadata and statistics. */
    public IndexInfo inspect(String index) {
        var info = DatadamCommands.map(commands.execute("IDX.INFO", index));
        var status = status(index, info);
        var fields = new ArrayList<IndexInfo.Field>();
        for (Object item : DatadamCommands.array(info.get("fields"))) {
            var values = DatadamCommands.array(item);
            if (values.size() < 2) throw new DataRetrievalFailureException("Malformed IDX field: " + index);
            String alias = values.get(0).toString();
            var type = fieldType(values.get(1));
            int position = 2;
            // A HASH field without AS has no separate path in IDX.INFO.
            String path = alias;
            if (position < values.size() && !"SEPARATOR".equals(values.get(position))) path = values.get(position++).toString();
            String separator = "";
            if (position < values.size()) {
                if (!"SEPARATOR".equals(values.get(position++)) || position + 1 != values.size())
                    throw new DataRetrievalFailureException("Malformed IDX field options: " + index);
                separator = values.get(position).toString();
            }
            fields.add(new IndexInfo.Field(path, alias, type, separator, type == IndexField.Type.TAG));
        }
        IndexInfo.SearchStatistics statistics = null;
        if (status.kind() == DatadamIndex.Kind.SEARCH) {
            var ft = DatadamCommands.map(commands.execute("FT.INFO", index));
            var attributes = DatadamCommands.array(ft.get("attributes"));
            if (attributes.size() != fields.size()) throw new DataRetrievalFailureException("IDX/FT field counts differ: " + index);
            for (int i = 0; i < attributes.size(); i++) {
                var attribute = attribute(DatadamCommands.array(attributes.get(i)));
                var previous = fields.get(i);
                var field = new IndexInfo.Field(required(attribute, "identifier"), required(attribute, "attribute"),
                    fieldType(attribute.get("type")), attribute.getOrDefault("SEPARATOR", "").toString(), attribute.containsKey("CASESENSITIVE"));
                if (!previous.path().equals(field.path()) || !previous.alias().equals(field.alias()) || previous.type() != field.type()
                    || !previous.separator().equals(field.separator()))
                    throw new DataRetrievalFailureException("IDX/FT schema changed or disagrees: " + index);
                fields.set(i, field);
            }
            statistics = new IndexInfo.SearchStatistics(number(ft, "num_docs"), number(ft, "num_records"));
        }
        return new IndexInfo(status, DatadamCommands.array(info.get("prefixes")).stream().map(Object::toString).toList(),
            number(info, "unique") != 0, fields, statistics);
    }
    /** Compares every declared index without creating, dropping, or waiting for it. */
    public List<IndexSchemaComparison> compare(Class<?> entity) {
        return Arrays.stream(entity.getAnnotationsByType(DatadamIndex.class)).map(index -> compare(entity, index.name())).toList();
    }
    public IndexSchemaComparison compare(Class<?> entity, String name) {
        var model = new EntityModel<>(entity);
        var index = Arrays.stream(entity.getAnnotationsByType(DatadamIndex.class)).filter(i -> i.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Index is not declared on entity: " + name));
        createArguments(model, index); // Validate declarations using the same rules as ensureIndexes, without DDL.
        IndexInfo actual;
        try { actual = inspect(name); }
        catch (InvalidDataAccessResourceUsageException e) {
            if (!Objects.equals(e.getMessage(), "ERR no such index '" + name + "'")) throw e;
            return new IndexSchemaComparison(name, null, List.of(new IndexSchemaComparison.Difference("exists", true, false)));
        }
        var differences = new ArrayList<IndexSchemaComparison.Difference>();
        difference(differences, "kind", index.kind(), actual.status().kind());
        difference(differences, "storage", model.storage(), actual.status().storage());
        difference(differences, "prefixes", List.of(model.prefix()), actual.prefixes());
        difference(differences, "unique", index.unique(), actual.unique());
        difference(differences, "fields.size", index.fields().length, actual.fields().size());
        for (int i = 0; i < Math.min(index.fields().length, actual.fields().size()); i++) {
            var expected = index.fields()[i]; var observed = actual.fields().get(i); String path = "fields[" + i + "].";
            difference(differences, path + "path", expected.path(), observed.path());
            difference(differences, path + "alias", expected.alias(), observed.alias());
            difference(differences, path + "type", expected.type(), observed.type());
            difference(differences, path + "separator", separator(model, index, expected), observed.separator());
            difference(differences, path + "caseSensitive", expected.type() == IndexField.Type.TAG
                && (index.kind() == DatadamIndex.Kind.COMPOUND || expected.caseSensitive()), observed.caseSensitive());
        }
        return new IndexSchemaComparison(name, actual, differences);
    }
    private void difference(List<IndexSchemaComparison.Difference> differences, String property, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) differences.add(new IndexSchemaComparison.Difference(property, expected, actual));
    }
    private IndexStatus status(String index, Map<String, Object> info) {
        try {
            String kind = required(info, "kind");
            if (!kind.equals("compound") && !kind.equals("search")) throw new IllegalArgumentException("Unknown index kind");
            return new IndexStatus(index, kind.equals("compound") ? DatadamIndex.Kind.COMPOUND : DatadamIndex.Kind.SEARCH,
                DatadamStorage.valueOf(required(info, "on")), IndexStatus.State.valueOf(required(info, "state").toUpperCase(Locale.ROOT)),
                number(info, "keys_scanned"), (String) info.get("error"));
        } catch (IllegalArgumentException | ClassCastException e) { throw new DataRetrievalFailureException("Malformed IDX.INFO: " + index, e); }
    }
    private String required(Map<String, Object> info, String key) {
        if (!(info.get(key) instanceof String value)) throw new DataRetrievalFailureException("Missing/invalid index attribute: " + key);
        return value;
    }
    private long number(Map<String, Object> info, String key) {
        if (!(info.get(key) instanceof Number value)) throw new DataRetrievalFailureException("Missing/invalid index number: " + key);
        return value.longValue();
    }
    private IndexField.Type fieldType(Object value) {
        try { return IndexField.Type.valueOf(Objects.toString(value)); }
        catch (IllegalArgumentException e) { throw new DataRetrievalFailureException("Unknown index field type", e); }
    }
    public void ensureIndexes(Class<?> entity, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        var model = new EntityModel<>(entity);
        for (var index : entity.getAnnotationsByType(DatadamIndex.class)) {
            List<Object> args = createArguments(model, index);
            try {
                commands.execute(index.kind() == DatadamIndex.Kind.COMPOUND ? "IDX.CREATE" : "FT.CREATE", args.toArray());
            } catch (InvalidDataAccessResourceUsageException e) {
                if (!e.getMessage().equals("ERR Index already exists")) throw e;
            }
            awaitReady(index.name(), timeout);
            verify(entity, index);
        }
    }
    // Migration support deliberately creates one declared target, never all indexes of a model.
    void validateDeclaration(Class<?> entity, String name) { createArguments(new EntityModel<>(entity), declaration(entity, name)); }
    void createDeclaredIndex(Class<?> entity, String name) {
        var declaration = declaration(entity, name);
        commands.execute(declaration.kind() == DatadamIndex.Kind.COMPOUND ? "IDX.CREATE" : "FT.CREATE",
            createArguments(new EntityModel<>(entity), declaration).toArray());
    }
    private DatadamIndex declaration(Class<?> entity, String name) {
        return Arrays.stream(entity.getAnnotationsByType(DatadamIndex.class)).filter(index -> index.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Index is not declared on entity: " + name));
    }
    private List<Object> createArguments(EntityModel<?> model, DatadamIndex index) {
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
        return args;
    }
    public void awaitReady(String index, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        long deadline = System.nanoTime() + timeout.toNanos();
        IndexStatus observed;
        do {
            observed = status(index);
            if (observed.ready()) return;
            if (observed.state() == IndexStatus.State.FAILED) throw new DataIntegrityViolationException("Index " + index + " failed: " + observed.error());
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DataAccessResourceFailureException("Interrupted waiting for index", e); }
        } while (System.nanoTime() < deadline);
        throw new QueryTimeoutException("Index " + index + " is still building; keys scanned=" + observed.keysScanned());
    }
    private void verify(Class<?> entity, DatadamIndex index) {
        var comparison = compare(entity, index.name());
        if (!comparison.compatible()) throw new DataIntegrityViolationException("Existing index schema differs from entity: "
            + index.name() + "; " + comparison.describe() + "; migrate explicitly");
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
