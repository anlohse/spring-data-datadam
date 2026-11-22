package com.anlohse.datadam.spring.repository;

import java.util.Optional;

import com.anlohse.datadam.spring.EntityModel;
import com.anlohse.datadam.spring.ReactiveDatadamTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.AbstractEntityInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;

public final class ReactiveDatadamRepositoryFactoryBean<R extends Repository<T, ID>, T, ID>
        extends RepositoryFactoryBeanSupport<R, T, ID> {
    private ReactiveDatadamTemplate template;

    public ReactiveDatadamRepositoryFactoryBean(Class<? extends R> repositoryInterface) { super(repositoryInterface); }
    @Autowired public void setTemplate(ReactiveDatadamTemplate template) { this.template = template; }
    @Override protected RepositoryFactorySupport createRepositoryFactory() { return new Factory(template); }

    private static final class Factory extends RepositoryFactorySupport {
        private final ReactiveDatadamTemplate template;
        private Factory(ReactiveDatadamTemplate template) { this.template = template; }
        @Override public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> type) {
            var model = new EntityModel<>(type);
            return new AbstractEntityInformation<T, ID>(type) {
                @Override @SuppressWarnings("unchecked") public ID getId(T entity) { return (ID) model.id(entity); }
                @Override @SuppressWarnings("unchecked") public Class<ID> getIdType() { return (Class<ID>) model.idType(); }
                @Override public boolean isNew(T entity) { return model.isNew(entity); }
            };
        }
        @Override protected Object getTargetRepository(RepositoryInformation info) {
            return new SimpleReactiveDatadamRepository<>(template, info.getDomainType());
        }
        @Override protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
            return SimpleReactiveDatadamRepository.class;
        }
        @Override protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.Key key, ValueExpressionDelegate delegate) {
            return Optional.of((method, metadata, factory, namedQueries) -> {
                throw new IllegalArgumentException("Reactive queries use native repository methods or interface default methods: " + method);
            });
        }
    }
}
