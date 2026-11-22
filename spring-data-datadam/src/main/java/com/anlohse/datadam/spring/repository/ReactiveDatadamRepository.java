package com.anlohse.datadam.spring.repository;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.ProjectionSpec;
import com.anlohse.datadam.spring.SearchQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/** Reactive CRUD plus bounded native queries. Query composition can use interface default methods. */
@NoRepositoryBean
public interface ReactiveDatadamRepository<T, ID> extends ReactiveCrudRepository<T, ID> {
    Mono<Boolean> persist(ID id);
    Mono<Long> count(String index, CompoundQuery query);
    Mono<Page<T>> query(String index, CompoundQuery query, Pageable page);
    Mono<Page<T>> search(String index, SearchQuery query, Pageable page);
    <R> Mono<Page<R>> projectQuery(String index, CompoundQuery query, Pageable page, ProjectionSpec<R> projection);
    <R> Mono<Page<R>> projectSearch(String index, SearchQuery query, Pageable page, ProjectionSpec<R> projection);
}
