package io.datadam.spring;

import java.util.*;
import java.util.function.Predicate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.dao.*;
import org.springframework.data.annotation.*;
import org.springframework.data.domain.Auditable;
import org.springframework.util.ReflectionUtils;
import io.datadam.spring.annotation.DatadamTimeToLive;
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
            || io.datadam.spring.auditing.DatadamAuditing.hasAuditingMetadata(model.type());
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
