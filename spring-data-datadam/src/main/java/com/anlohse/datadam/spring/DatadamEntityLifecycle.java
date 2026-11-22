package com.anlohse.datadam.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import com.anlohse.datadam.spring.auditing.DatadamAuditing;
import com.anlohse.datadam.spring.callback.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.*;
import org.springframework.data.mapping.callback.EntityCallbacks;

/** Shared local preparation and acknowledgment handling; contains no transport calls. */
final class DatadamEntityLifecycle {
    static final String SAVE_WITH_TTL = loadScript("save-json-with-ttl.lua");
    static final String SAVE_VERSIONED = loadScript("save-versioned-json.lua");
    static final String SAVE_HASH = loadScript("save-hash.lua");
    private final ObjectMapper mapper;
    private final Map<String, DatadamIdGenerator> idGenerators;
    private final EntityCallbacks callbacks;
    private final DatadamAuditing auditing;

    DatadamEntityLifecycle(ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators,
                          EntityCallbacks callbacks, DatadamAuditing auditing) {
        this.mapper = mapper;
        this.idGenerators = Map.copyOf(idGenerators);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.auditing = auditing;
    }

    record PreparedSave<S>(String command, List<Object> arguments, Function<Object, S> complete) {
        PreparedSave {
            arguments = List.copyOf(arguments);
        }
    }

    <T, S extends T> PreparedSave<S> prepareSave(S entity, EntityModel<T> model) {
        Objects.requireNonNull(entity);
        validateModel(model);
        model.validateVersionState(entity);
        boolean isNew = model.isNew(entity);
        Object initialId = model.id(entity);
        Number initialVersion = model.version(entity);
        var context = new DatadamSaveContext(model.type(), model.prefix(), model.hasMissingId(entity) ? null : model.key(initialId), isNew);
        var creation = auditing != null && !isNew ? auditing.snapshot(entity, true) : Map.<String, Object>of();
        S working = callbacks.callback(BeforeConvertCallback.class, entity, context);
        checkCallbackEntity(entity, working, model, initialId, isNew);
        checkVersion(working, model, initialVersion);
        if (auditing != null) {
            auditing.validate(working.getClass());
            auditing.assertUnchanged(creation, working);
            working = auditing.audit(working, isNew);
        }
        var auditValues = auditing == null ? Map.<String, Object>of() : auditing.snapshot(working, false);
        working = callbacks.callback(BeforeSaveCallback.class, working, context);
        checkCallbackEntity(entity, working, model, initialId, isNew);
        checkVersion(working, model, initialVersion);
        if (auditing != null) auditing.assertUnchanged(auditValues, working);
        var ttl = model.timeToLiveMilliseconds(working);
        boolean generate = model.generatesId() && model.hasMissingId(working);
        if (generate && !isNew) throw new IllegalArgumentException("An entity marked as not new cannot have an absent generated ID");
        Object id = generate ? generator(model).generateId(model.idGenerationContext()) : model.id(working);
        if (generate) model.validateGeneratedId(id);
        String key = model.key(id);
        Number nextVersion = model.isVersioned() ? model.nextVersion(working) : null;
        Map<String, Object> managedValues = new LinkedHashMap<>();
        if (generate) managedValues.put(model.idProperty(), id);
        if (model.isVersioned()) managedValues.put(model.versionProperty(), nextVersion);
        S result = managedValues.isEmpty() ? working : model.recordWithValues(working, managedValues);
        if (generate && model.type().isRecord() && !Objects.equals(model.id(result), id))
            throw new IllegalArgumentException("Record constructor must preserve the generated ID");
        if (model.isVersioned() && model.type().isRecord() && !Objects.equals(model.version(result), nextVersion))
            throw new IllegalArgumentException("Record constructor must preserve the managed version");
        boolean insertOnly = generate || entity instanceof org.springframework.data.domain.Persistable<?> && isNew;
        if (auditing != null) auditing.assertUnchanged(auditValues, result);
        try {
            String json = managedValues.isEmpty() ? mapper.writeValueAsString(result) : jsonWithManagedValues(result, managedValues);
            if (model.isVersioned()) {
                var encoded = mapper.readTree(json).get(jsonProperty(model.type(), model.versionProperty()));
                if (encoded == null || !encoded.isIntegralNumber() || encoded.longValue() != nextVersion.longValue())
                    throw new IllegalArgumentException("@Version must serialize as its exact JSON integer value");
            }
            String command;
            var args = new ArrayList<Object>();
            if (model.isHash()) {
                command = "EVAL";
                Collections.addAll(args, SAVE_HASH, 1, key,
                    model.isVersioned() ? isNew ? "CREATE" : "UPDATE" : insertOnly ? "CREATE" : "UPSERT",
                    model.isVersioned() ? jsonProperty(model.type(), model.versionProperty()) : "",
                    initialVersion == null ? "" : initialVersion, ttl.isPresent() ? ttl.getAsLong() : "");
                args.addAll(new HashEntityCodec(mapper).encode(json));
            } else if (model.isVersioned()) {
                command = "EVAL";
                Collections.addAll(args, SAVE_VERSIONED, 1, key, versionPath(model),
                    initialVersion == null ? "" : initialVersion, isNew ? "CREATE" : "UPDATE",
                    json, ttl.isPresent() ? ttl.getAsLong() : "");
            } else if (ttl.isPresent()) {
                command = "EVAL";
                Collections.addAll(args, SAVE_WITH_TTL, 1, key, json, ttl.getAsLong(), insertOnly ? "NX" : "");
            } else {
                command = "JSON.SET";
                Collections.addAll(args, key, "$", json);
                if (insertOnly) args.add("NX");
            }
            return new PreparedSave<>(command, args, reply -> {
                if (model.isVersioned() || model.isHash()) {
                    if (!(reply instanceof Number number) || number.longValue() != 1) {
                        if (model.isVersioned())
                            throw new OptimisticLockingFailureException("Versioned save failed: missing entity, duplicate creation, or stale version");
                        throw new DuplicateKeyException("Cannot insert HASH entity: its ID already exists");
                    }
                }
                if (reply == null) throw new DuplicateKeyException("Cannot insert entity: its ID already exists");
                if (generate) model.applyGeneratedId(result, id);
                if (model.isVersioned() && !model.type().isRecord()) model.applyVersion(result, nextVersion);
                try {
                    S returned = callbacks.callback(AfterSaveCallback.class, result, new DatadamSaveContext(model.type(), model.prefix(), key, isNew));
                    checkCallbackEntity(entity, returned, model, id, null);
                    checkVersion(returned, model, nextVersion);
                    if (auditing != null) auditing.assertUnchanged(auditValues, returned);
                    return returned;
                } catch (RuntimeException e) { throw new AfterSaveCallbackException(key, e); }
            });
        }
        catch (JsonProcessingException e) { throw new InvalidDataAccessApiUsageException("Cannot serialize entity", e); }
    }
    public void validateModel(EntityModel<?> model) {
        if (model.isHash()) {
            jsonProperty(model.type(), model.idProperty());
            for (var property : mapper.getDeserializationConfig().introspect(mapper.constructType(model.type())).findProperties())
                if (property.getPrimaryType().hasRawClass(Object.class))
                    throw new IllegalArgumentException("HASH root properties require declared types; Object is ambiguous: " + property.getName());
                else if (property.getPrimaryType().hasRawClass(char[].class))
                    throw new IllegalArgumentException("HASH char[] properties are ambiguous; use String or List<Character>");
        }
        if (model.generatesId()) generator(model).validate(model.idGenerationContext());
        if (model.isVersioned()) {
            jsonProperty(model.type(), model.versionProperty());
            var properties = mapper.getDeserializationConfig().introspect(mapper.constructType(model.type())).findProperties();
            if (properties.stream().noneMatch(p -> p.getInternalName().equals(model.versionProperty()) && p.couldDeserialize()))
                throw new IllegalArgumentException("@Version must be deserializable from JSON");
        }
        if (auditing != null) {
            auditing.validate(model.type());
            var properties = mapper.getSerializationConfig().introspect(mapper.constructType(model.type())).findProperties();
            for (String name : auditing.properties(model.type()))
                if (properties.stream().noneMatch(p -> p.getInternalName().equals(name) && p.couldSerialize()))
                    throw new IllegalArgumentException("Audit property must be serialized in JSON: " + name);
        }
    }
    private <T> void checkCallbackEntity(T original, T candidate, EntityModel<T> model, Object id, Boolean newState) {
        if (candidate == null || !original.getClass().isInstance(candidate)) throw new IllegalArgumentException("Callback must return a compatible, non-null entity");
        if (!Objects.equals(id, model.id(candidate))) throw new IllegalArgumentException("Callback must not change entity identity");
        if (newState != null && model.isNew(candidate) != newState) throw new IllegalArgumentException("Pre-save callback must not change entity new-state detection");
    }
    private <T> void checkVersion(T entity, EntityModel<T> model, Number expected) {
        if (model.isVersioned() && !Objects.equals(expected, model.version(entity)))
            throw new IllegalArgumentException("Callback must not change entity version");
    }
    private DatadamIdGenerator generator(EntityModel<?> model) {
        var generator = idGenerators.get(model.idGeneratorName());
        if (generator == null) throw new IllegalArgumentException("No DatadamIdGenerator named '" + model.idGeneratorName() + "' for " + model.type().getName());
        return generator;
    }
    String jsonProperty(Class<?> type, String name) {
        return mapper.getSerializationConfig().introspect(mapper.constructType(type)).findProperties().stream()
            .filter(p -> p.getInternalName().equals(name) && p.couldSerialize()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Managed value must be a serialized JSON property: " + name)).getName();
    }
    String versionPath(EntityModel<?> model) {
        String name = jsonProperty(model.type(), model.versionProperty());
        return "$['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }
    private String jsonWithManagedValues(Object entity, Map<String, Object> values) throws JsonProcessingException {
        var tree = mapper.valueToTree(entity);
        if (!(tree instanceof ObjectNode object)) throw new IllegalArgumentException("Managed values require an entity serialized as a JSON object");
        values.forEach((name, value) -> object.set(jsonProperty(entity.getClass(), name), mapper.valueToTree(value)));
        return mapper.writeValueAsString(object);
    }
    private static String loadScript(String name) {
        try { return new ClassPathResource("com/anlohse/datadam/spring/" + name).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    <T> T read(Object json, EntityModel<T> model, String key) {
        T loaded;
        try { loaded = mapper.readValue(json.toString(), model.type()); }
        catch (JsonProcessingException e) { throw new DataRetrievalFailureException("Cannot deserialize " + model.type().getName(), e); }
        Object id = model.id(loaded);
        Number version = model.version(loaded);
        var auditValues = auditing == null ? Map.<String, Object>of() : auditing.snapshot(loaded, false);
        T returned = callbacks.callback(AfterConvertCallback.class, loaded, key);
        checkCallbackEntity(loaded, returned, model, id, null);
        checkVersion(returned, model, version);
        if (auditing != null) auditing.assertUnchanged(auditValues, returned);
        return returned;
    }
}
