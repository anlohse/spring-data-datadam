package com.anlohse.datadam.spring;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.auditing.DatadamAuditing;
import com.anlohse.datadam.spring.reactive.ReactiveDatadamOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.mapping.callback.EntityCallbacks;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive entity mapping over native commands. The caller owns the transport connection.
 * Auditing is opt-in and resolves its actor from the subscriber context.
 * This template does not register imperative entity callbacks.
 */
public final class ReactiveDatadamTemplate {
    private final ReactiveDatadamOperations operations;
    private final ObjectMapper mapper;
    private final Map<String, DatadamIdGenerator> generators;
    private final ReactiveAuditorAware<?> auditor;
    private final Clock clock;

    public ReactiveDatadamTemplate(ReactiveDatadamOperations operations, ObjectMapper mapper) {
        this(operations, mapper, Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()), null, Clock.systemUTC());
    }

    /** A non-null auditor enables date and actor auditing. An empty actor still populates dates. */
    public ReactiveDatadamTemplate(ReactiveDatadamOperations operations, ObjectMapper mapper,
            Map<String, DatadamIdGenerator> generators, ReactiveAuditorAware<?> auditor, Clock clock) {
        this.operations = Objects.requireNonNull(operations);
        this.mapper = Objects.requireNonNull(mapper);
        this.generators = Map.copyOf(generators);
        this.auditor = auditor;
        this.clock = Objects.requireNonNull(clock);
    }

    public void validateModel(EntityModel<?> model) {
        lifecycle(Optional.empty()).validateModel(model);
    }

    public <T, S extends T> Mono<S> save(S entity, EntityModel<T> model) {
        return onDemand(() -> actor().flatMap(actor -> {
            var prepared = lifecycle(actor).prepareSave(entity, model);
            return operations.executeScalar(prepared.command(), prepared.arguments())
                .switchIfEmpty(Mono.error(new DuplicateKeyException("Entity ID already exists")))
                .map(prepared.complete());
        }));
    }

    public <T> Mono<T> findById(Object id, EntityModel<T> model) {
        return onDemand(() -> {
            String key = model.key(id);
            Mono<String> json = model.isHash()
                ? operations.executeArray("HGETALL", List.of(key))
                    .filter(fields -> !fields.isEmpty()).map(fields -> new HashEntityCodec(mapper).decode(fields, model.type()))
                : operations.jsonGet(key);
            return json.map(value -> lifecycle(Optional.empty()).read(value, model, key));
        });
    }

    public <T> Mono<Void> delete(T entity, EntityModel<T> model) {
        return onDemand(() -> {
            validateModel(model);
            if (!model.isVersioned()) return deleteById(model.id(entity), model);
            model.validateVersionState(entity);
            if (model.isNew(entity)) return Mono.error(new OptimisticLockingFailureException("Cannot delete an unsaved entity"));
            var policy = lifecycle(Optional.empty());
            List<Object> args = model.isHash()
                ? List.of(DatadamEntityLifecycle.SAVE_HASH, 1, model.key(model.id(entity)), "DELETE",
                    policy.jsonProperty(model.type(), model.versionProperty()), model.version(entity), "")
                : List.of(DatadamEntityLifecycle.SAVE_VERSIONED, 1, model.key(model.id(entity)),
                    policy.versionPath(model), model.version(entity), "DELETE", "", "");
            return operations.executeScalar("EVAL", args).flatMap(reply -> {
                if (!(reply instanceof Long count) || count != 1)
                    return Mono.error(new OptimisticLockingFailureException("Versioned delete failed"));
                return Mono.<Void>empty();
            });
        });
    }

    public <T> Mono<Void> deleteById(Object id, EntityModel<T> model) {
        return onDemand(() -> {
            if (model.isVersioned()) return findById(id, model).flatMap(entity -> delete(entity, model));
            return model.isHash()
                ? operations.executeScalar("EVAL", List.of(DatadamEntityLifecycle.SAVE_HASH, 1, model.key(id),
                    "DELETE_ANY", "", "", "")).then()
                : operations.executeScalar("DEL", List.of(model.key(id))).then();
        });
    }

    public Mono<Boolean> persist(Object id, EntityModel<?> model) {
        return onDemand(() -> operations.executeScalar("PERSIST", List.of(model.key(id))).map(value -> value.equals(1L)));
    }

    public <T> Flux<T> findAll(EntityModel<T> model) {
        return keys(model).concatMap(id -> findById(id, model), 1);
    }

    public Flux<String> keys(EntityModel<?> model) {
        return Flux.defer(() -> scan("0", model).expand(page -> page.get(0).equals("0")
                ? Mono.empty() : scan(page.get(0).toString(), model), 1)
            .concatMapIterable(page -> DatadamCommands.array(page.get(1)), 1)
            .map(key -> {
                if (!(key instanceof String text) || !text.startsWith(model.prefix()))
                    throw new DataRetrievalFailureException("SCAN returned an invalid entity key");
                return text.substring(model.prefix().length());
            }).distinct());
    }

    private Mono<List<Object>> scan(String cursor, EntityModel<?> model) {
        return operations.executeArray("SCAN", List.of(cursor, "MATCH", model.prefix() + "*", "COUNT", 256,
            "TYPE", model.isHash() ? "hash" : "ReJSON-RL"));
    }

    public <T> Mono<Long> count(String index, CompoundQuery query, EntityModel<T> model) {
        return onDemand(() -> {
            model.index(index, DatadamIndex.Kind.COMPOUND);
            return operations.count(index, query);
        });
    }

    public <T> Mono<Page<T>> query(String index, CompoundQuery query, Pageable page, EntityModel<T> model) {
        return onDemand(() -> {
            bounds(page);
            if (page.getSort().isSorted()) throw new IllegalArgumentException("Use CompoundQuery.desc() for IDX ordering");
            model.index(index, DatadamIndex.Kind.COMPOUND);
            return operations.count(index, query).flatMap(total -> operations.query(index, query, page.getOffset(), page.getPageSize())
                .map(rows -> new PageImpl<>(entities(rows, model), page, total)));
        });
    }

    public <T> Mono<Page<T>> search(String index, SearchQuery query, Pageable page, EntityModel<T> model) {
        return onDemand(() -> {
            bounds(page);
            model.index(index, DatadamIndex.Kind.SEARCH);
            return operations.search(index, sorted(query, page), page.getOffset(), page.getPageSize())
                .map(result -> new PageImpl<>(entities(result.content(), model), page, result.total()));
        });
    }

    public <R> Mono<Page<R>> project(String index, CompoundQuery query, Pageable page,
            EntityModel<?> model, ProjectionSpec<R> projection) {
        return onDemand(() -> {
            bounds(page);
            if (page.getSort().isSorted()) throw new IllegalArgumentException("Use CompoundQuery.desc() for IDX ordering");
            var plan = new ProjectionPlan<>(mapper, model, model.index(index, DatadamIndex.Kind.COMPOUND), projection);
            return operations.count(index, query).flatMap(total ->
                operations.query(index, query, page.getOffset(), page.getPageSize(), plan.selectors())
                    .map(rows -> new PageImpl<>(projected(rows, plan), page, total)));
        });
    }

    public <R> Mono<Page<R>> project(String index, SearchQuery query, Pageable page,
            EntityModel<?> model, ProjectionSpec<R> projection) {
        return onDemand(() -> {
            bounds(page);
            var plan = new ProjectionPlan<>(mapper, model, model.index(index, DatadamIndex.Kind.SEARCH), projection);
            return operations.search(index, sorted(query, page), page.getOffset(), page.getPageSize(), plan.selectors())
                .map(result -> new PageImpl<>(projected(result.content(), plan), page, result.total()));
        });
    }

    private <T> List<T> entities(List<ReactiveDatadamOperations.Row> rows, EntityModel<T> model) {
        return rows.stream().map(row -> {
            if (!row.key().startsWith(model.prefix())) throw new DataRetrievalFailureException("Index returned a different entity prefix");
            String json = model.isHash() ? new HashEntityCodec(mapper).decode(pairs(row.fields()), model.type()) : row.fields().get("$");
            if (json == null) throw new DataRetrievalFailureException("Missing document content");
            return lifecycle(Optional.empty()).read(json, model, row.key());
        }).toList();
    }

    private static <R> List<R> projected(List<ReactiveDatadamOperations.Row> rows, ProjectionPlan<R> plan) {
        return rows.stream().map(row -> plan.read(row.key(), pairs(row.fields()))).toList();
    }

    private static List<Object> pairs(Map<String, String> fields) {
        var pairs = new ArrayList<Object>();
        fields.forEach((key, value) -> { pairs.add(key); pairs.add(value); });
        return pairs;
    }

    private Mono<Optional<Object>> actor() {
        return auditor == null ? Mono.just(Optional.empty())
            : Mono.defer(() -> Mono.from(auditor.getCurrentAuditor())).map(value -> Optional.of((Object) value))
                .defaultIfEmpty(Optional.empty());
    }

    private DatadamEntityLifecycle lifecycle(Optional<Object> actor) {
        var auditing = auditor == null ? null
            : new DatadamAuditing(() -> Optional.of(Instant.now(clock)), () -> actor, true, true);
        return new DatadamEntityLifecycle(mapper, generators, EntityCallbacks.create(), auditing);
    }

    private static void bounds(Pageable page) {
        if (page == null || page.isUnpaged()) throw new IllegalArgumentException("A bounded page is required");
    }

    private static SearchQuery sorted(SearchQuery query, Pageable page) {
        if (query.sort().isSorted() && page.getSort().isSorted() && !query.sort().equals(page.getSort()))
            throw new IllegalArgumentException("Conflicting FT sort orders");
        return new SearchQuery(query.expression(), query.parameters(), query.sort().isSorted() ? query.sort() : page.getSort());
    }

    /** Defers multi-command composition until demand, preserving the subscriber's Reactor context. */
    private static <T> Mono<T> onDemand(Supplier<Mono<T>> operation) {
        return Mono.create(sink -> {
            var subscription = Disposables.swap();
            var started = new AtomicBoolean();
            sink.onDispose(subscription);
            sink.onRequest(request -> {
                if (started.compareAndSet(false, true) && !subscription.isDisposed()) {
                    subscription.update(Mono.defer(operation).contextWrite(sink.contextView())
                        .subscribe(sink::success, sink::error, sink::success));
                }
            });
        });
    }
}
