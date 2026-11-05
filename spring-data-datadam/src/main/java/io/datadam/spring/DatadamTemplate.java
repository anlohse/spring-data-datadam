package io.datadam.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import io.datadam.spring.annotation.DatadamIndex;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.datadam.spring.auditing.DatadamAuditing;
import io.datadam.spring.callback.*;
import org.springframework.data.mapping.callback.EntityCallbacks;

public class DatadamTemplate {
    private static final String SAVE_WITH_TTL = loadSaveWithTtl();
    private final DatadamCommands commands;
    private final ObjectMapper mapper;
    private final Map<String, DatadamIdGenerator> idGenerators;
    private final EntityCallbacks callbacks;
    private final DatadamAuditing auditing;
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper) {
        this(commands, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()));
    }
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators) {
        this(commands, mapper, idGenerators, EntityCallbacks.create(), null);
    }
    public DatadamTemplate(DatadamCommands commands, ObjectMapper mapper, Map<String, DatadamIdGenerator> idGenerators,
        EntityCallbacks callbacks, DatadamAuditing auditing) {
        this.commands = commands; this.mapper = mapper; this.idGenerators = Map.copyOf(idGenerators);
        this.callbacks = Objects.requireNonNull(callbacks); this.auditing = auditing;
    }
    public DatadamCommands commands() { return commands; }
    public <T, S extends T> S save(S entity, EntityModel<T> model) {
        Objects.requireNonNull(entity);
        validateModel(model);
        boolean isNew = model.isNew(entity);
        Object initialId = model.id(entity);
        var context = new DatadamSaveContext(model.type(), model.prefix(), model.hasMissingId(entity) ? null : model.key(initialId), isNew);
        var creation = auditing != null && !isNew ? auditing.snapshot(entity, true) : Map.<String, Object>of();
        S working = callbacks.callback(BeforeConvertCallback.class, entity, context);
        checkCallbackEntity(entity, working, model, initialId, isNew);
        if (auditing != null) {
            auditing.validate(working.getClass());
            auditing.assertUnchanged(creation, working);
            working = auditing.audit(working, isNew);
        }
        var auditValues = auditing == null ? Map.<String, Object>of() : auditing.snapshot(working, false);
        working = callbacks.callback(BeforeSaveCallback.class, working, context);
        checkCallbackEntity(entity, working, model, initialId, isNew);
        if (auditing != null) auditing.assertUnchanged(auditValues, working);
        var ttl = model.timeToLiveMilliseconds(working);
        boolean generate = model.generatesId() && model.hasMissingId(working);
        if (generate && !isNew) throw new IllegalArgumentException("An entity marked as not new cannot have an absent generated ID");
        Object id = generate ? generator(model).generateId(model.idGenerationContext()) : model.id(working);
        if (generate) model.validateGeneratedId(id);
        String key = model.key(id);
        S result = generate ? model.recordWithId(working, id) : working;
        if (generate && model.type().isRecord() && !Objects.equals(model.id(result), id))
            throw new IllegalArgumentException("Record constructor must preserve the generated ID");
        boolean insertOnly = generate || entity instanceof org.springframework.data.domain.Persistable<?> && isNew;
        if (auditing != null) auditing.assertUnchanged(auditValues, result);
        try {
            String json = generate ? jsonWithId(result, model, id) : mapper.writeValueAsString(result);
            Object reply;
            if (ttl.isPresent()) reply = commands.execute("EVAL", SAVE_WITH_TTL, 1, key, json, ttl.getAsLong(), insertOnly ? "NX" : "");
            else if (insertOnly) reply = commands.execute("JSON.SET", key, "$", json, "NX");
            else reply = commands.execute("JSON.SET", key, "$", json);
            if (reply == null) throw new DuplicateKeyException("Cannot insert entity: its ID already exists");
            if (generate) model.applyGeneratedId(result, id);
            try {
                S returned = callbacks.callback(AfterSaveCallback.class, result, new DatadamSaveContext(model.type(), model.prefix(), key, isNew));
                checkCallbackEntity(entity, returned, model, id, null);
                if (auditing != null) auditing.assertUnchanged(auditValues, returned);
                return returned;
            } catch (RuntimeException e) { throw new AfterSaveCallbackException(key, e); }
        }
        catch (JsonProcessingException e) { throw new InvalidDataAccessApiUsageException("Cannot serialize entity", e); }
    }
    public void validateModel(EntityModel<?> model) {
        if (model.generatesId()) generator(model).validate(model.idGenerationContext());
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
    private DatadamIdGenerator generator(EntityModel<?> model) {
        var generator = idGenerators.get(model.idGeneratorName());
        if (generator == null) throw new IllegalArgumentException("No DatadamIdGenerator named '" + model.idGeneratorName() + "' for " + model.type().getName());
        return generator;
    }
    private String jsonWithId(Object entity, EntityModel<?> model, Object id) throws JsonProcessingException {
        var property = mapper.getSerializationConfig().introspect(mapper.constructType(entity.getClass())).findProperties().stream()
            .filter(p -> p.getInternalName().equals(model.idProperty()) && p.couldSerialize()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Generated @Id must be a serialized JSON property: " + model.idProperty()));
        var tree = mapper.valueToTree(entity);
        if (!(tree instanceof ObjectNode object)) throw new IllegalArgumentException("Generated IDs require an entity serialized as a JSON object");
        object.set(property.getName(), mapper.valueToTree(id));
        return mapper.writeValueAsString(object);
    }
    /** Remove expiration only. Future saves with an explicit/default positive TTL apply it again. */
    public boolean persist(Object id, EntityModel<?> model) {
        return ((Number) commands.execute("PERSIST", model.key(id))).longValue() == 1;
    }
    private static String loadSaveWithTtl() {
        try { return new ClassPathResource("io/datadam/spring/save-json-with-ttl.lua").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public <T> Optional<T> findById(Object id, EntityModel<T> model) {
        Object json = commands.execute("JSON.GET", model.key(id)); // no path: document, not JSONPath array
        return json == null ? Optional.empty() : Optional.of(read(json, model, model.key(id)));
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
    /** Resolve Java property names through Jackson, including @JsonProperty and naming strategies. */
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
            Object json = commands.execute("JSON.GET", key);
            if (json != null) result.add(read(json, model, key));
        }
        return result;
    }
    public List<String> keys(EntityModel<?> model) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        do {
            var result = DatadamCommands.array(commands.execute("SCAN", cursor, "MATCH", model.prefix() + "*", "COUNT", 256, "TYPE", "ReJSON-RL"));
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
            var fields = DatadamCommands.map(array.get(i + 1));
            Object json = fields.get("$");
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
        var auditValues = auditing == null ? Map.<String, Object>of() : auditing.snapshot(loaded, false);
        T returned = callbacks.callback(AfterConvertCallback.class, loaded, key);
        checkCallbackEntity(loaded, returned, model, id, null);
        if (auditing != null) auditing.assertUnchanged(auditValues, returned);
        return returned;
    }
}
