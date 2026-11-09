package com.anlohse.datadam.spring.repository;

import java.util.List;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.ProjectionSpec;
import com.anlohse.datadam.spring.SearchQuery;
import com.anlohse.datadam.spring.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.*;

/**
 * Blocking Spring Data repository with native compound-index and TAG/NUMERIC search operations.
 * Global findAll/count use a prefix scan; prefer bounded index queries for business reads.
 * Batch writes are sequential and can succeed partially. No transaction manager is implied.
 *
 * @param <T> entity type
 * @param <ID> entity identifier type
 */
@NoRepositoryBean
public interface DatadamRepository<T, ID> extends ListCrudRepository<T, ID> {
    /** Remove this key's expiration; returns false for a missing or already persistent key. */
    boolean persist(ID id);
    Page<T> query(String index, CompoundQuery query, Pageable pageable);
    long count(String index, CompoundQuery query);
    List<T> lookup(String index, Object... values);
    Page<T> search(String index, SearchQuery query, Pageable pageable);
    <R> Page<R> projectQuery(String index, CompoundQuery query, Pageable pageable, ProjectionSpec<R> projection);
    <R> Page<R> projectSearch(String index,SearchQuery query,Pageable pageable,ProjectionSpec<R> projection);
}
