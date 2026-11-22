package com.anlohse.datadam.spring.repository;

import java.util.Objects;

import com.anlohse.datadam.spring.*;
import org.reactivestreams.Publisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class SimpleReactiveDatadamRepository<T, ID> implements ReactiveDatadamRepository<T, ID> {
    private final ReactiveDatadamTemplate template;
    private final EntityModel<T> model;

    public SimpleReactiveDatadamRepository(ReactiveDatadamTemplate template, Class<T> type) {
        this.template = Objects.requireNonNull(template);
        this.model = new EntityModel<>(type);
        template.validateModel(model);
    }

    @Override public <S extends T> Mono<S> save(S entity) { return template.save(entity, model); }
    @Override public <S extends T> Flux<S> saveAll(Iterable<S> entities) { return saveAll(Flux.fromIterable(entities)); }
    @Override public <S extends T> Flux<S> saveAll(Publisher<S> entities) { return Flux.from(entities).concatMap(this::save, 0); }
    @Override public Mono<T> findById(ID id) { return template.findById(id, model); }
    @Override public Mono<T> findById(Publisher<ID> ids) { return Mono.from(ids).flatMap(this::findById); }
    @Override public Mono<Boolean> existsById(ID id) { return findById(id).hasElement(); }
    @Override public Mono<Boolean> existsById(Publisher<ID> ids) { return Mono.from(ids).flatMap(this::existsById); }
    @Override public Flux<T> findAll() { return template.findAll(model); }
    @Override public Flux<T> findAllById(Iterable<ID> ids) { return findAllById(Flux.fromIterable(ids)); }
    @Override public Flux<T> findAllById(Publisher<ID> ids) { return Flux.from(ids).concatMap(this::findById, 0); }
    @Override public Mono<Long> count() { return template.keys(model).count(); }
    @Override public Mono<Void> deleteById(ID id) { return template.deleteById(id, model); }
    @Override public Mono<Void> deleteById(Publisher<ID> ids) { return Mono.from(ids).flatMap(this::deleteById); }
    @Override public Mono<Void> delete(T entity) { return template.delete(entity, model); }
    @Override public Mono<Void> deleteAllById(Iterable<? extends ID> ids) {
        return Flux.fromIterable(ids).concatMap(this::deleteById, 0).then();
    }
    @Override public Mono<Void> deleteAll(Iterable<? extends T> entities) { return deleteAll(Flux.fromIterable(entities)); }
    @Override public Mono<Void> deleteAll(Publisher<? extends T> entities) {
        return Flux.from(entities).concatMap(this::delete, 0).then();
    }
    @Override public Mono<Void> deleteAll() { return findAll().concatMap(this::delete, 0).then(); }
    @Override public Mono<Boolean> persist(ID id) { return template.persist(id, model); }
    @Override public Mono<Long> count(String index, CompoundQuery query) { return template.count(index, query, model); }
    @Override public Mono<Page<T>> query(String index, CompoundQuery query, Pageable page) {
        return template.query(index, query, page, model);
    }
    @Override public Mono<Page<T>> search(String index, SearchQuery query, Pageable page) {
        return template.search(index, query, page, model);
    }
    @Override public <R> Mono<Page<R>> projectQuery(String index, CompoundQuery query, Pageable page, ProjectionSpec<R> projection) {
        return template.project(index, query, page, model, projection);
    }
    @Override public <R> Mono<Page<R>> projectSearch(String index, SearchQuery query, Pageable page, ProjectionSpec<R> projection) {
        return template.project(index, query, page, model, projection);
    }
}
