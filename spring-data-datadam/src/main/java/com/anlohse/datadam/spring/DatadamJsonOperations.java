package com.anlohse.datadam.spring;

import java.util.*;
import java.util.function.Predicate;

import com.anlohse.datadam.spring.auditing.DatadamAuditing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.dao.*;
import org.springframework.data.domain.Auditable;
import org.springframework.util.ReflectionUtils;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;

/** Native JSON operations bound to an entity prefix. Reads return values, not materialized entities. */
public final class DatadamJsonOperations<T> {
    private final DatadamCommands commands;
    private final ObjectMapper mapper;
    private final EntityModel<T> model;
    private final Predicate<Class<?>> writeCallbacks;
    private final boolean managed;
    @SuppressWarnings("deprecation")
    DatadamJsonOperations(DatadamCommands commands, ObjectMapper mapper, EntityModel<T> model, Predicate<Class<?>> writeCallbacks) {
        this.commands = commands; this.model = model; this.writeCallbacks = writeCallbacks;
        this.mapper = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        managed = model.isVersioned() || Auditable.class.isAssignableFrom(model.type())
            || DatadamAuditing.hasAuditingMetadata(model.type());
    }
    public <V> JsonReadResult<V> read(Object id, JsonPath<V> path) {
        Objects.requireNonNull(path);
        Object reply = commands.execute("JSON.GET", model.key(id), path.expression());
        if (reply == null) return new JsonReadResult<>(false, List.of());
        try {
            JsonNode node = mapper.readTree(reply.toString());
            if (node == null) throw new DataRetrievalFailureException("Expected JSON text");
            var values = new ArrayList<V>();
            if (path.isJsonPath()) {
                if (!node.isArray()) throw new DataRetrievalFailureException("Expected JSONPath match array");
                for (var match : node) values.add(convert(match, path));
            } else values.add(convert(node, path));
            return new JsonReadResult<>(true, values);
        } catch (JsonProcessingException e) { throw new DataRetrievalFailureException("Cannot decode JSON path as " + path.type().getTypeName(), e); }
    }
    /** True when JSON.SET acknowledges a write; false if its path cannot be created. JSON null is a value. */
    public <V> boolean set(Object id, JsonPath<V> path, V value) {
        checkWrite(path);
        try {
            String json = mapper.writeValueAsString(value);
            convert(mapper.readTree(json), path); // validate the declared value type before submitting a command
            Object result = commands.execute("JSON.SET", model.key(id), path.expression(), json);
            if (result == null) return false;
            if (!"OK".equals(result)) throw new DataRetrievalFailureException("Unexpected JSON.SET reply");
            return true;
        } catch (JsonProcessingException e) { throw new InvalidDataAccessApiUsageException("Cannot encode value for JSON path", e); }
    }
    /** Number of removed values (zero or one for these singular paths). Never removes the root key. */
    public long remove(Object id, JsonPath<?> path) {
        checkWrite(path);
        Object reply = commands.execute("JSON.DEL", model.key(id), path.expression());
        if (!(reply instanceof Number count)) throw new DataRetrievalFailureException("Expected JSON.DEL count");
        return count.longValue();
    }
    /** Numeric promotion follows the server, not the declared input type (int64 overflow can produce a float). */
    public List<Number> increment(Object id, JsonPath<? extends Number> path, Number amount) {
        return arithmetic("JSON.NUMINCRBY", id, path, amount);
    }
    public List<Number> multiply(Object id, JsonPath<? extends Number> path, Number factor) {
        return arithmetic("JSON.NUMMULTBY", id, path, factor);
    }
    private List<Number> arithmetic(String command, Object id, JsonPath<? extends Number> path, Number amount) {
        checkWrite(path);
        JsonNode number = encode(Objects.requireNonNull(amount), null);
        if (!number.isNumber() || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Operand must serialize as a finite JSON number");
        String key = model.key(id);
        Object reply = commands.execute(command, key, path.expression(), number.toString());
        try {
            JsonNode result = mapper.readTree(reply.toString());
            var values = new ArrayList<Number>();
            if (path.isJsonPath()) {
                if (!result.isArray()) throw new DataRetrievalFailureException("Expected numeric match array");
                for (var value : result) values.add(numeric(value));
            } else values.add(numeric(result));
            return immutable(values);
        } catch (JsonProcessingException | RuntimeException e) { throw new JsonMutationDecodingException(key, command, e); }
    }
    private Number numeric(JsonNode node) {
        if (node.isNull()) return null;
        if (!node.isNumber()) throw new DataRetrievalFailureException("Expected JSON number");
        return node.numberValue();
    }
    public <V> List<Long> arrayAppend(Object id, JsonPath<List<V>> path, List<? extends V> values) {
        return arrayAdd("JSON.ARRAPPEND", id, path, null, values);
    }
    public <V> List<Long> arrayInsert(Object id, JsonPath<List<V>> path, long index, List<? extends V> values) {
        return arrayAdd("JSON.ARRINSERT", id, path, index, values);
    }
    private <V> List<Long> arrayAdd(String command, Object id, JsonPath<List<V>> path, Long index, List<? extends V> values) {
        checkWrite(path); JavaType element = elementType(path);
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("At least one array value is required");
        var args = new ArrayList<Object>(); Collections.addAll(args, model.key(id), path.expression());
        if (index != null) args.add(index);
        for (var value : values) args.add(encode(value, element).toString());
        Object reply = commands.execute(command, args.toArray());
        return mutationLengths(command, args.get(0).toString(), path, reply);
    }
    public List<Long> arrayTrim(Object id, JsonPath<? extends List<?>> path, long start, long stop) {
        checkWrite(path); elementType(path); String key = model.key(id);
        return mutationLengths("JSON.ARRTRIM", key, path, commands.execute("JSON.ARRTRIM", key, path.expression(), start, stop));
    }
    public <V> List<JsonPopResult<V>> arrayPop(Object id, JsonPath<List<V>> path) { return arrayPop(id, path, -1); }
    public <V> List<JsonPopResult<V>> arrayPop(Object id, JsonPath<List<V>> path, long index) {
        checkWrite(path); JavaType element = elementType(path); String key = model.key(id);
        Object reply = commands.execute("JSON.ARRPOP", key, path.expression(), index);
        try {
            var results = new ArrayList<JsonPopResult<V>>();
            for (var value : matches(reply, path)) {
                V decoded = value == null ? null : mapper.readValue(value.toString(), element);
                results.add(new JsonPopResult<>(value != null, decoded));
            }
            return immutable(results);
        } catch (JsonProcessingException | RuntimeException e) { throw new JsonMutationDecodingException(key, "JSON.ARRPOP", e); }
    }
    public List<Long> arrayLength(Object id, JsonPath<? extends List<?>> path) {
        elementType(path);
        return lengths(commands.execute("JSON.ARRLEN", model.key(id), path.expression()), path);
    }
    public <V> List<Long> arrayIndex(Object id, JsonPath<List<V>> path, V value) { return arrayIndex(id, path, value, 0, 0); }
    public <V> List<Long> arrayIndex(Object id, JsonPath<List<V>> path, V value, long start, long stop) {
        String json = encode(value, elementType(path)).toString();
        return lengths(commands.execute("JSON.ARRINDEX", model.key(id), path.expression(), json, start, stop), path);
    }
    /** Object merge patch; null-valued members are removed. True means OK, including a JSONPath with no matches. */
    public boolean merge(Object id, JsonPath<?> path, Map<String, ?> patch) {
        checkWrite(path);
        JsonNode object = mergeNode(Objects.requireNonNull(patch));
        if (!object.isObject()) throw new IllegalArgumentException("Merge patch must serialize as a JSON object");
        Object reply = commands.execute("JSON.MERGE", model.key(id), path.expression(), object.toString());
        if (!"OK".equals(reply)) throw new JsonMutationDecodingException(model.key(id), "JSON.MERGE", new DataRetrievalFailureException("Expected OK"));
        return true;
    }
    private JsonNode mergeNode(Map<?, ?> patch) {
        var object = mapper.createObjectNode();
        patch.forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Merge members require String names");
            object.set(name, value instanceof Map<?, ?> nested ? mergeNode(nested) : encode(value, null));
        });
        return object;
    }
    private JavaType elementType(JsonPath<?> path) {
        JavaType type = mapper.constructType(Objects.requireNonNull(path).type());
        if (!List.class.isAssignableFrom(type.getRawClass()) || type.getContentType() == null)
            throw new IllegalArgumentException("Array operations require JsonPath<List<V>>");
        return type.getContentType();
    }
    private JsonNode encode(Object value, JavaType type) {
        try {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(value));
            if (type != null) mapper.readValue(node.toString(), type);
            return node;
        } catch (JsonProcessingException e) { throw new InvalidDataAccessApiUsageException("Cannot encode JSON operation value", e); }
    }
    private List<Long> mutationLengths(String command, String key, JsonPath<?> path, Object reply) {
        try { return lengths(reply, path); }
        catch (RuntimeException e) { throw new JsonMutationDecodingException(key, command, e); }
    }
    private List<Long> lengths(Object reply, JsonPath<?> path) {
        var values = new ArrayList<Long>();
        for (var value : matches(reply, path)) {
            if (value == null) values.add(null);
            else if (value instanceof Number number) values.add(number.longValue());
            else throw new DataRetrievalFailureException("Expected RESP integer");
        }
        return immutable(values);
    }
    private List<?> matches(Object reply, JsonPath<?> path) {
        return path.isJsonPath() ? DatadamCommands.array(reply) : Collections.singletonList(reply);
    }
    private static <V> List<V> immutable(List<V> values) { return Collections.unmodifiableList(values); }
    private <V> V convert(JsonNode node, JsonPath<V> path) throws JsonProcessingException {
        return mapper.readValue(node.toString(), mapper.constructType(path.type()));
    }
    private void checkWrite(JsonPath<?> path) {
        Objects.requireNonNull(path);
        if (managed) throw managed();
        if (writeCallbacks.test(model.type())) throw new InvalidDataAccessApiUsageException("Partial writes cannot bypass entity write callbacks; use save");
        String first = JsonMutationPath.firstMember(path.expression());
        var properties = mapper.getSerializationConfig().introspect(mapper.constructType(model.type())).findProperties();
        // Refuse models whose identity cannot be mapped to one explicit root property.
        var identity = properties.stream().filter(p -> p.getInternalName().equals(model.idProperty()) && p.couldSerialize()).findFirst()
            .orElseThrow(() -> new InvalidDataAccessApiUsageException("Partial writes require a serialized @Id property"));
        if (mapper.getSerializationConfig().getAnnotationIntrospector().findUnwrappingNameTransformer(identity.getPrimaryMember()) != null)
            throw new InvalidDataAccessApiUsageException("Partial writes require @Id mapped to one root property");
        if (propertyNames(model.idProperty()).contains(first)) throw new InvalidDataAccessApiUsageException("Partial writes cannot modify or remove @Id");
        ReflectionUtils.doWithFields(model.type(), field -> {
            if (field.isAnnotationPresent(DatadamTimeToLive.class) && propertyNames(field.getName()).contains(first))
                throw new InvalidDataAccessApiUsageException("Partial writes cannot change the TTL policy; use save");
        });
    }
    private Set<String> propertyNames(String internalName) {
        Set<String> names = new HashSet<>(); names.add(internalName);
        for (var property : mapper.getSerializationConfig().introspect(mapper.constructType(model.type())).findProperties())
            if (property.getInternalName().equals(internalName)) names.add(property.getName());
        for (var property : mapper.getDeserializationConfig().introspect(mapper.constructType(model.type())).findProperties()) {
            if (!property.getInternalName().equals(internalName)) continue;
            names.add(property.getName());
            if (property.getPrimaryMember() != null) {
                var aliases = mapper.getDeserializationConfig().getAnnotationIntrospector().findPropertyAliases(property.getPrimaryMember());
                if (aliases != null) aliases.forEach(alias -> names.add(alias.getSimpleName()));
            }
        }
        return names;
    }
    private InvalidDataAccessApiUsageException managed() {
        return new InvalidDataAccessApiUsageException("Partial writes on versioned or audited entities require their lifecycle; use save");
    }
}
