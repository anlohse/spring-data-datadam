package io.datadam.spring.repository;

import java.util.List;
import io.datadam.spring.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.*;

@NoRepositoryBean
public interface DatadamRepository<T, ID> extends ListCrudRepository<T, ID> {
    /** Remove this key's expiration; returns false for a missing or already persistent key. */
    boolean persist(ID id);
    Page<T> query(String index, CompoundQuery query, Pageable pageable);
    long count(String index, CompoundQuery query);
    List<T> lookup(String index, Object... values);
    Page<T> search(String index, SearchQuery query, Pageable pageable);
    <R> Page<R> projectQuery(String index,CompoundQuery query,Pageable pageable,ProjectionSpec<R> projection);
    <R> Page<R> projectSearch(String index,SearchQuery query,Pageable pageable,ProjectionSpec<R> projection);
}
