package io.datadam.spring.repository;

import java.util.*;
import io.datadam.spring.*;
import org.springframework.data.domain.*;

public class SimpleDatadamRepository<T, ID> implements DatadamRepository<T, ID> {
    private final DatadamTemplate template;
    private final EntityModel<T> model;
    public SimpleDatadamRepository(DatadamTemplate template, Class<T> type) {
        this.template = template; model = new EntityModel<>(type); template.validateModel(model);
    }
    public <S extends T> S save(S entity) { return template.save(entity, model); }
    public <S extends T> List<S> saveAll(Iterable<S> entities) { List<S> result = new ArrayList<>(); entities.forEach(e -> result.add(save(e))); return result; }
    public Optional<T> findById(ID id) { return template.findById(id, model); }
    public boolean persist(ID id) { return template.persist(id, model); }
    public boolean existsById(ID id) { return findById(id).isPresent(); }
    public List<T> findAll() { return template.findAll(model); }
    public List<T> findAllById(Iterable<ID> ids) { List<T> result = new ArrayList<>(); ids.forEach(id -> findById(id).ifPresent(result::add)); return result; }
    public long count() { return template.keys(model).size(); }
    public void deleteById(ID id) { template.commands().execute("DEL", model.key(id)); }
    public void delete(T entity) { template.commands().execute("DEL", model.key(model.id(entity))); }
    public void deleteAllById(Iterable<? extends ID> ids) { ids.forEach(this::deleteById); }
    public void deleteAll(Iterable<? extends T> entities) { entities.forEach(this::delete); }
    public void deleteAll() { template.keys(model).forEach(key -> template.commands().execute("DEL", key)); }
    public Page<T> query(String index, CompoundQuery query, Pageable page) { return template.query(index, query, page, model); }
    public long count(String index, CompoundQuery query) { return template.count(index, query, model); }
    public List<T> lookup(String index, Object... values) { return template.lookup(index, model, values); }
    public Page<T> search(String index, SearchQuery query, Pageable page) { return template.search(index, query, page, model); }
}
