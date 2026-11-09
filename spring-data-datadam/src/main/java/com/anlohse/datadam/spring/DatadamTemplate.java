package com.anlohse.datadam.spring;

import com.anlohse.datadam.spring.callback.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.anlohse.datadam.spring.auditing.DatadamAuditing;
import com.anlohse.datadam.spring.callback.*;
import org.springframework.data.mapping.callback.EntityCallbacks;

/**
 * Blocking native Datadam operations and entity lifecycle mapping.
 * Each command acquires one pooled connection. A sequence of calls is not a transaction;
 * transport failures must be reconciled before retrying a mutation.
 * Use entity models for full writes and projection specifications for selected-field reads.
 */
public class DatadamTemplate {
    private static final String SAVE_WITH_TTL = loadSaveWithTtl();
    private static final String SAVE_VERSIONED = loadScript("save-versioned-json.lua");
    private static final String SAVE_HASH = loadScript("save-hash.lua");
    private final DatadamCommands commands;
    private final ObjectMapper mapper;
    private final Map<String, DatadamIdGenerator> idGenerators;
    private final EntityCallbacks callbacks;
    private final DatadamAuditing auditing;
    private final java.util.function.Predicate<Class<?>> partialWriteCallbacks;
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper) {
        this(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()));
    }
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators) {
        this(commands, mapper, idGenerators, EntityCallbacks.create(), null, type -> false);
    }
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators,
        EntityCallbacks callbacks, DatadamAuditing auditing) {
        // EntityCallbacks does not expose its registrations. Be conservative for this opaque manual configuration.
        this(commands, mapper, idGenerators, callbacks, auditing, type -> true);
    }
    /** The predicate must identify every entity type with registered write callbacks; unknown generic types must match all. */
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators,
        EntityCallbacks callbacks, DatadamAuditing auditing, java.util.function.Predicate<Class<?>> partialWriteCallbacks) {
        this.commands = commands; this.mapper = mapper; this.idGenerators = Map.copyOf(idGenerators);
        this.callbacks = Objects.requireNonNull(callbacks); this.auditing = auditing;
        this.partialWriteCallbacks = Objects.requireNonNull(partialWriteCallbacks);
    }
    public DatadamCommands commands() { return commands; }
    public <T> DatadamJsonOperations<T> json(Class<T> type) {
        var model = new EntityModel<>(type);
        if (model.isHash()) throw new IllegalArgumentException("JSON operations require JSON storage");
        return new DatadamJsonOperations<>(commands, mapper, model, partialWriteCallbacks);
    }
    public <T, S extends T> S save(S entity, EntityModel<T> model) {
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
            Object reply;
            if (model.isHash()) {
                var args = new ArrayList<Object>();
                Collections.addAll(args, SAVE_HASH, 1, key, model.isVersioned() ? isNew ? "CREATE" : "UPDATE" : insertOnly ? "CREATE" : "UPSERT",
                    model.isVersioned() ? jsonProperty(model.type(), model.versionProperty()) : "", initialVersion == null ? "" : initialVersion,
                    ttl.isPresent() ? ttl.getAsLong() : "");
                args.addAll(new HashEntityCodec(mapper).encode(json));
                reply = commands.execute("EVAL", args.toArray());
                if (!(reply instanceof Number number) || number.longValue() != 1) {
                    if (model.isVersioned()) throw new OptimisticLockingFailureException("Versioned HASH save failed: duplicate creation, missing entity or stale version");
                    throw new DuplicateKeyException("Cannot insert HASH entity: its ID already exists");
                }
            }
            else if (model.isVersioned()) {
                reply = commands.execute("EVAL", SAVE_VERSIONED, 1, key, versionPath(model),
                    initialVersion == null ? "" : initialVersion, isNew ? "CREATE" : "UPDATE", json, ttl.isPresent() ? ttl.getAsLong() : "");
                if (!(reply instanceof Number number) || number.longValue() != 1)
                    throw new OptimisticLockingFailureException("Versioned save failed: missing entity, duplicate creation, or stale version");
            }
            else if (ttl.isPresent()) reply = commands.execute("EVAL", SAVE_WITH_TTL, 1, key, json, ttl.getAsLong(), insertOnly ? "NX" : "");
            else if (insertOnly) reply = commands.execute("JSON.SET", key, "$", json, "NX");
            else reply = commands.execute("JSON.SET", key, "$", json);
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
    private String jsonProperty(Class<?> type, String name) {
        return mapper.getSerializationConfig().introspect(mapper.constructType(type)).findProperties().stream()
            .filter(p -> p.getInternalName().equals(name) && p.couldSerialize()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Managed value must be a serialized JSON property: " + name)).getName();
    }
    private String versionPath(EntityModel<?> model) {
        String name = jsonProperty(model.type(), model.versionProperty());
        return "$['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }
    private String jsonWithManagedValues(Object entity, Map<String, Object> values) throws JsonProcessingException {
        var tree = mapper.valueToTree(entity);
        if (!(tree instanceof ObjectNode object)) throw new IllegalArgumentException("Managed values require an entity serialized as a JSON object");
        values.forEach((name, value) -> object.set(jsonProperty(entity.getClass(), name), mapper.valueToTree(value)));
        return mapper.writeValueAsString(object);
    }
    /** Compare the entity's version and delete within the same isolated server operation. */
    public <T> void delete(T entity, EntityModel<T> model) {
        Objects.requireNonNull(entity);
        validateModel(model);
        if (!model.isVersioned()) { deleteById(model.id(entity), model); return; }
        model.validateVersionState(entity);
        if (model.isNew(entity)) throw new OptimisticLockingFailureException("Cannot delete an unsaved versioned entity");
        Object reply = model.isHash()
            ? commands.execute("EVAL", SAVE_HASH, 1, model.key(model.id(entity)), "DELETE", jsonProperty(model.type(), model.versionProperty()), model.version(entity), "")
            : commands.execute("EVAL", SAVE_VERSIONED, 1, model.key(model.id(entity)), versionPath(model), model.version(entity), "DELETE", "", "");
        if (!(reply instanceof Number number) || number.longValue() != 1)
            throw new OptimisticLockingFailureException("Versioned delete failed: missing entity or stale version");
    }
    public <T> void deleteById(Object id, EntityModel<T> model) {
        if (model.isVersioned()) { findById(id, model).ifPresent(entity -> delete(entity, model)); return; }
        if (model.isHash()) commands.execute("EVAL", SAVE_HASH, 1, model.key(id), "DELETE_ANY", "", "", "");
        else commands.execute("DEL", model.key(id));
    }
    /** Remove expiration only. Future saves with an explicit/default positive TTL apply it again. */
    public boolean persist(Object id, EntityModel<?> model) {
        return ((Number) commands.execute("PERSIST", model.key(id))).longValue() == 1;
    }
    private static String loadSaveWithTtl() {
        return loadScript("save-json-with-ttl.lua");
    }
    private static String loadScript(String name) {
        try { return new ClassPathResource("com/anlohse/datadam/spring/" + name).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public <T> Optional<T> findById(Object id, EntityModel<T> model) {
        Object json = loadKey(model.key(id), model);
        return json == null ? Optional.empty() : Optional.of(read(json, model, model.key(id)));
    }
    private Object loadKey(String key, EntityModel<?> model) {
        if (!model.isHash()) return commands.execute("JSON.GET", key);
        Object fields = commands.execute("HGETALL", key);
        return DatadamCommands.array(fields).isEmpty() ? null : new HashEntityCodec(mapper).decode(fields, model.type());
    }
    public <T> Page<T> query(String index, CompoundQuery query, Pageable pageable, EntityModel<T> model) {
        model.index(index, DatadamIndex.Kind.COMPOUND);
        if (pageable.getSort().isSorted()) throw new IllegalArgumentException("IDX ordering follows index fields; use CompoundQuery.desc()");
        long total = count(index, query, model);
        return new PageImpl<>(queryContent(index, query, pageable.isPaged() ? pageable.getOffset() : 0,
            pageable.isPaged() ? pageable.getPageSize() : -1, model), pageable, total);
    }
    /** Native content-only query: no COUNT scan; used by derived List and Slice methods. */
    public <T> List<T> queryContent(String index, CompoundQuery query, long offset, int limit, EntityModel<T> model) {
        model.index(index, DatadamIndex.Kind.COMPOUND);
        if (offset < 0 || limit < -1) throw new IllegalArgumentException("Invalid IDX page bounds");
        var args = query.arguments(index);
        if (query.descending()) args.add("DESC");
        Collections.addAll(args, "LIMIT", offset, limit);
        return documents(commands.execute("IDX.QUERY", args.toArray()), 0, model);
    }
    public boolean exists(String index, CompoundQuery query, EntityModel<?> model) {
        model.index(index, DatadamIndex.Kind.COMPOUND);
        var args = query.arguments(index);
        Collections.addAll(args, "LIMIT", 0, 1, "NOCONTENT");
        return !DatadamCommands.array(commands.execute("IDX.QUERY", args.toArray())).isEmpty();
    }
    public <T> long count(String index, CompoundQuery query, EntityModel<T> model) {
        model.index(index, DatadamIndex.Kind.COMPOUND);
        return ((Number) commands.execute("IDX.COUNT", query.arguments(index).toArray())).longValue();
    }
    public <T> List<T> lookup(String index, EntityModel<T> model, Object... values) {
        model.index(index, DatadamIndex.Kind.COMPOUND);
        List<Object> args = new ArrayList<>(List.of(index));
        Collections.addAll(args, values);
        Object result = commands.execute("IDX.GET", args.toArray());
        return result == null ? List.of() : documents(result, 0, model);
    }
    public <T> Page<T> search(String index, SearchQuery query, Pageable pageable, EntityModel<T> model) {
        model.index(index, DatadamIndex.Kind.SEARCH);
        Sort sort = query.sort().isSorted() ? query.sort() : pageable.getSort();
        // Validate both entry points consistently.
        new SearchQuery(query.expression(), query.parameters(), sort);
        if (query.sort().isSorted() && pageable.getSort().isSorted() && !query.sort().equals(pageable.getSort()))
            throw new IllegalArgumentException("Conflicting search sort orders");
        if (pageable.isUnpaged()) throw new IllegalArgumentException("FT.SEARCH requires a bounded page");
        var result = searchResults(index, new SearchQuery(query.expression(), query.parameters(), sort), pageable.getOffset(), pageable.getPageSize(), model);
        return new PageImpl<>(result.content(), pageable, result.total());
    }
    public <T> SearchResults<T> searchResults(String index, SearchQuery query, long offset, int limit, EntityModel<T> model) {
        model.index(index, DatadamIndex.Kind.SEARCH);
        if (offset < 0 || limit < 0) throw new IllegalArgumentException("Invalid FT page bounds");
        List<Object> args = new ArrayList<>(List.of(index, query.expression()));
        if (query.sort().isSorted()) { var order = query.sort().iterator().next(); Collections.addAll(args, "SORTBY", order.getProperty(), order.getDirection()); }
        Collections.addAll(args, "LIMIT", offset, limit);
        if (!query.parameters().isEmpty()) {
            Collections.addAll(args, "PARAMS", query.parameters().size() * 2);
            query.parameters().forEach((key, value) -> Collections.addAll(args, key, value));
            Collections.addAll(args, "DIALECT", 2);
        }
        var response = DatadamCommands.array(commands.execute("FT.SEARCH", args.toArray()));
        long total = ((Number) response.get(0)).longValue();
        return new SearchResults<>(documents(response, 1, model), total);
    }
    public record SearchResults<T>(List<T> content, long total) {
        public SearchResults { content = List.copyOf(content); }
    }
    public void validateProjection(String index,EntityModel<?> model,ProjectionSpec<?> projection,DatadamIndex.Kind kind){
        new ProjectionPlan<>(mapper,model,model.index(index,kind),projection);
    }
    public <R> Page<R> projectQuery(String index,CompoundQuery query,Pageable pageable,EntityModel<?> model,ProjectionSpec<R> projection){
        validateProjection(index,model,projection,DatadamIndex.Kind.COMPOUND);
        if(pageable.getSort().isSorted())throw new IllegalArgumentException("IDX ordering follows index fields; use CompoundQuery.desc()");
        long total=count(index,query,model);
        return new PageImpl<>(projectQueryContent(index,query,pageable.isPaged()?pageable.getOffset():0,pageable.isPaged()?pageable.getPageSize():-1,model,projection),pageable,total);
    }
    public <R> List<R> projectQueryContent(String index,CompoundQuery query,long offset,int limit,EntityModel<?> model,ProjectionSpec<R> projection){
        var plan=new ProjectionPlan<>(mapper,model,model.index(index,DatadamIndex.Kind.COMPOUND),projection);
        if(offset<0||limit< -1)throw new IllegalArgumentException("Invalid IDX projection bounds");
        var args=query.arguments(index);if(query.descending())args.add("DESC");
        Collections.addAll(args,"LIMIT",offset,limit,plan.option(),plan.selectors().size());args.addAll(plan.selectors());
        return projectedDocuments(commands.execute("IDX.QUERY",args.toArray()),0,plan);
    }
    public <R> Page<R> projectSearch(String index,SearchQuery query,Pageable pageable,EntityModel<?> model,ProjectionSpec<R> projection){
        if(pageable.isUnpaged())throw new IllegalArgumentException("FT projections require a bounded page");
        Sort sort=query.sort().isSorted()?query.sort():pageable.getSort();
        if(query.sort().isSorted()&&pageable.getSort().isSorted()&&!query.sort().equals(pageable.getSort()))throw new IllegalArgumentException("Conflicting search sort orders");
        var result=projectSearchResults(index,new SearchQuery(query.expression(),query.parameters(),sort),pageable.getOffset(),pageable.getPageSize(),model,projection);
        return new PageImpl<>(result.content(),pageable,result.total());
    }
    public <R> SearchResults<R> projectSearchResults(String index,SearchQuery query,long offset,int limit,EntityModel<?> model,ProjectionSpec<R> projection){
        var plan=new ProjectionPlan<>(mapper,model,model.index(index,DatadamIndex.Kind.SEARCH),projection);
        if(offset<0||limit<0)throw new IllegalArgumentException("Invalid FT projection bounds");
        var args=new ArrayList<Object>(List.of(index,query.expression()));
        if(query.sort().isSorted()){var order=query.sort().iterator().next();Collections.addAll(args,"SORTBY",order.getProperty(),order.getDirection());}
        Collections.addAll(args,"LIMIT",offset,limit,plan.option(),plan.selectors().size());args.addAll(plan.selectors());
        if(!query.parameters().isEmpty()){Collections.addAll(args,"PARAMS",query.parameters().size()*2);query.parameters().forEach((name,value)->Collections.addAll(args,name,value));args.add("DIALECT");args.add(2);}
        var response=DatadamCommands.array(commands.execute("FT.SEARCH",args.toArray()));
        if(response.isEmpty()||!(response.get(0) instanceof Number total))throw new DataRetrievalFailureException("Missing FT projection total");
        return new SearchResults<>(projectedDocuments(response,1,plan),total.longValue());
    }
    private <R> List<R> projectedDocuments(Object response,int offset,ProjectionPlan<R> plan){
        var rows=DatadamCommands.array(response);if((rows.size()-offset)%2!=0)throw new DataRetrievalFailureException("Malformed projection reply");
        var result=new ArrayList<R>();for(int i=offset;i<rows.size();i+=2)result.add(plan.read(rows.get(i).toString(),rows.get(i+1)));
        return List.copyOf(result);
    }
    /** Resolve Java property names through Jackson, including @JsonProperty and naming strategies. */
    public String indexPath(EntityModel<?> model, String property) {
        if (!model.isHash()) return jsonPath(model.type(), property);
        if (property.contains(".")) throw new IllegalArgumentException("HASH derived queries require root fields; nested objects are JSON inside one HASH field");
        return jsonProperty(model.type(), property);
    }
    public String jsonPath(Class<?> entity, String property) {
        StringBuilder path = new StringBuilder("$");
        var type = mapper.constructType(entity);
        for (String segment : property.split("\\.")) {
            var definition = mapper.getSerializationConfig().introspect(type).findProperties().stream()
                .filter(p -> p.getInternalName().equals(segment) && p.couldSerialize()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unmapped JSON property: " + property));
            String name = definition.getName();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Derived queries require simple JSON member names: " + name);
            path.append('.').append(name);
            type = definition.getPrimaryType();
            if (type.isContainerType()) throw new IllegalArgumentException("Derived queries do not support collection/map properties: " + property);
        }
        return path.toString();
    }
    public String scalarValue(Object value) {
        var node = mapper.valueToTree(Objects.requireNonNull(value, "Query values cannot be null"));
        if (node.isNull() || node.isContainerNode()) throw new IllegalArgumentException("Expected scalar query value");
        return node.isTextual() ? node.textValue() : node.toString();
    }
    public <T> List<T> findAll(EntityModel<T> model) {
        List<T> result = new ArrayList<>();
        for (String key : keys(model)) {
            Object json = loadKey(key, model);
            if (json != null) result.add(read(json, model, key));
        }
        return result;
    }
    public List<String> keys(EntityModel<?> model) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        do {
            var result = DatadamCommands.array(commands.execute("SCAN", cursor, "MATCH", model.prefix() + "*", "COUNT", 256, "TYPE", model.isHash() ? "hash" : "ReJSON-RL"));
            cursor = result.get(0).toString();
            DatadamCommands.array(result.get(1)).forEach(key -> keys.add(key.toString()));
        } while (!cursor.equals("0"));
        return keys;
    }
    private <T> List<T> documents(Object result, int offset, EntityModel<T> model) {
        var array = DatadamCommands.array(result);
        List<T> entities = new ArrayList<>();
        if ((array.size() - offset) % 2 != 0) throw new DataRetrievalFailureException("Malformed document reply");
        for (int i = offset; i < array.size(); i += 2) {
            String key = array.get(i).toString();
            if (!key.startsWith(model.prefix())) throw new DataRetrievalFailureException("Index returned a different entity prefix");
            Object json = model.isHash() ? new HashEntityCodec(mapper).decode(array.get(i + 1), model.type())
                : DatadamCommands.map(array.get(i + 1)).get("$");
            if (json == null) throw new DataRetrievalFailureException("Expected JSON document content");
            entities.add(read(json, model, key));
        }
        return entities;
    }
    private <T> T read(Object json, EntityModel<T> model, String key) {
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
