package com.anlohse.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.dao.*;

/** One serialized root property per HASH field; containers are JSON text, nulls are absent. */
final class HashEntityCodec {
    private final ObjectMapper mapper;
    @SuppressWarnings("deprecation")
    HashEntityCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    }
    List<Object> encode(String json) throws JsonProcessingException {
        var tree = mapper.readTree(json);
        if (!tree.isObject()) throw new IllegalArgumentException("HASH entities must serialize as JSON objects");
        var result = new ArrayList<Object>();
        tree.fields().forEachRemaining(entry -> {
            var node = entry.getValue();
            if (!node.isNull()) { result.add(entry.getKey()); result.add(node.isTextual() ? node.textValue() : node.toString()); }
        });
        if (result.isEmpty()) throw new IllegalArgumentException("A HASH entity must have persistent fields");
        return result;
    }
    String decode(Object reply, Class<?> type) {
        var fields = DatadamCommands.map(reply);
        var tree = mapper.createObjectNode();
        var properties = mapper.getDeserializationConfig().introspect(mapper.constructType(type)).findProperties();
        fields.forEach((name, value) -> {
            var property = properties.stream().filter(p -> p.getName().equals(name) || aliases(p).contains(name)).findFirst();
            if (property.isEmpty()) { tree.put(name, value.toString()); return; }
            JavaType target = property.get().getPrimaryType();
            Class<?> raw = target.getRawClass();
            boolean structured = raw != byte[].class && (target.isContainerType()
                || !raw.isPrimitive() && !raw.isEnum() && !raw.getName().startsWith("java.") && raw != Object.class);
            boolean numeric = Number.class.isAssignableFrom(raw) || raw.isPrimitive() && raw != char.class;
            boolean temporal = java.time.temporal.TemporalAccessor.class.isAssignableFrom(raw)
                || java.time.temporal.TemporalAmount.class.isAssignableFrom(raw) || java.util.Date.class.isAssignableFrom(raw);
            try {
                if (structured || numeric || raw == Boolean.class || raw == Object.class) tree.set(name, mapper.readTree(value.toString()));
                else if (temporal) {
                    JsonNode encoded;
                    try { encoded = mapper.readTree(value.toString()); }
                    catch (JsonProcessingException ignored) { encoded = null; }
                    if (encoded != null && (encoded.isNumber() || encoded.isArray())) tree.set(name, encoded);
                    else tree.put(name, value.toString());
                }
                else tree.put(name, value.toString());
            } catch (JsonProcessingException e) { throw new DataRetrievalFailureException("Cannot decode HASH field " + name, e); }
        });
        return tree.toString();
    }
    private Set<String> aliases(com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition property) {
        if (property.getPrimaryMember() == null) return Set.of();
        var aliases = mapper.getDeserializationConfig().getAnnotationIntrospector().findPropertyAliases(property.getPrimaryMember());
        if (aliases == null) return Set.of();
        var result = new HashSet<String>(); aliases.forEach(a -> result.add(a.getSimpleName())); return result;
    }
}
