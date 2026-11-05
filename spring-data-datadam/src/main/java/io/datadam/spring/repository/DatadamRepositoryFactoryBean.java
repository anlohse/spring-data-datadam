package io.datadam.spring.repository;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.*;
import org.springframework.data.repository.core.support.*;
import org.springframework.data.repository.query.*;

public class DatadamRepositoryFactoryBean<R extends Repository<T, ID>, T, ID> extends RepositoryFactoryBeanSupport<R, T, ID> {
    private DatadamTemplate template;
    public DatadamRepositoryFactoryBean(Class<? extends R> repositoryInterface) { super(repositoryInterface); }
    @Autowired public void setTemplate(DatadamTemplate template) { this.template = template; }
    @Override protected RepositoryFactorySupport createRepositoryFactory() { return new Factory(template); }

    private static class Factory extends RepositoryFactorySupport {
        private final DatadamTemplate template;
        Factory(DatadamTemplate template) { this.template = template; }
        @Override public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> type) {
            var model = new EntityModel<>(type);
            return new AbstractEntityInformation<T, ID>(type) {
                @SuppressWarnings("unchecked") public ID getId(T entity) { return (ID) model.id(entity); }
                @SuppressWarnings("unchecked") public Class<ID> getIdType() { return (Class<ID>) model.idType(); }
                @Override public boolean isNew(T entity) { return model.isNew(entity); }
            };
        }
        @Override protected Object getTargetRepository(RepositoryInformation info) { return new SimpleDatadamRepository<>(template, info.getDomainType()); }
        @Override protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) { return SimpleDatadamRepository.class; }
        @Override protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.Key key, ValueExpressionDelegate delegate) {
            return Optional.of((method, metadata, projectionFactory, namedQueries) -> {
                boolean declared = method.isAnnotationPresent(IdxQuery.class) || method.isAnnotationPresent(FtQuery.class);
                if (key != QueryLookupStrategy.Key.CREATE && declared)
                    return new DeclaredQuery(method, metadata, projectionFactory, template);
                if (key == QueryLookupStrategy.Key.USE_DECLARED_QUERY)
                    throw new IllegalArgumentException("No declared Datadam query on " + method);
                return new DerivedDatadamQuery(method, metadata, projectionFactory, template);
            });
        }
    }
    private static class DeclaredQuery implements RepositoryQuery {
        private final Method method;
        private final QueryMethod queryMethod;
        private final EntityModel<?> model;
        private final DatadamTemplate template;
        private final IdxQuery idx;
        private final FtQuery ft;
        DeclaredQuery(Method method, RepositoryMetadata metadata, ProjectionFactory projections, DatadamTemplate template) {
            this.method = method; this.template = template;
            queryMethod = new QueryMethod(method, metadata, projections);
            model = new EntityModel<>(metadata.getDomainType());
            idx = method.getAnnotation(IdxQuery.class); ft = method.getAnnotation(FtQuery.class);
            if ((idx == null) == (ft == null)) throw new IllegalArgumentException("Declare exactly one @IdxQuery or @FtQuery on " + method);
            model.index(idx != null ? idx.index() : ft.index(), idx != null ? DatadamIndex.Kind.COMPOUND : DatadamIndex.Kind.SEARCH);
            if (method.getReturnType() != Page.class && method.getReturnType() != List.class)
                throw new IllegalArgumentException("Declared queries must return Page<T> or List<T>: " + method);
            if (Arrays.stream(method.getParameterTypes()).filter(Pageable.class::isAssignableFrom).count() > 1)
                throw new IllegalArgumentException("Only one Pageable is supported");
            if (idx != null) bindConditions(idx.value(), null); // validate declaration at startup
        }
        public QueryMethod getQueryMethod() { return queryMethod; }
        public Object execute(Object[] parameters) {
            Pageable pageable = Arrays.stream(parameters).filter(Pageable.class::isInstance).map(Pageable.class::cast).findFirst().orElse(PageRequest.of(0, 100));
            Page<?> result;
            if (idx != null) {
                CompoundQuery query = bindConditions(idx.value(), parameters);
                if (idx.descending()) query = query.desc();
                result = template.query(idx.index(), query, pageable, model);
            } else {
                var matcher = Pattern.compile("\\?(\\d+)").matcher(ft.value());
                Map<String, Object> bindings = new LinkedHashMap<>();
                StringBuffer expression = new StringBuffer();
                while (matcher.find()) {
                    int position = Integer.parseInt(matcher.group(1));
                    bindings.put("p" + position, parameter(parameters, position));
                    matcher.appendReplacement(expression, Matcher.quoteReplacement("$p" + position));
                }
                matcher.appendTail(expression);
                result = template.search(ft.index(), new SearchQuery(expression.toString(), bindings, Sort.unsorted()), pageable, model);
            }
            return method.getReturnType() == Page.class ? result : result.getContent();
        }
        private CompoundQuery bindConditions(String expression, Object[] parameters) {
            CompoundQuery query = CompoundQuery.all();
            if (expression.isBlank()) return query;
            String[] tokens = expression.trim().split("\\s+");
            if (tokens.length % 3 != 0) throw new IllegalArgumentException("IDX query requires field operator ?N triples");
            for (int i = 0; i < tokens.length; i += 3) {
                if (!tokens[i + 2].matches("\\?\\d+")) throw new IllegalArgumentException("IDX query values must use ?N bindings");
                int position = Integer.parseInt(tokens[i + 2].substring(1));
                if (position >= method.getParameterCount() || Pageable.class.isAssignableFrom(method.getParameterTypes()[position])) throw new IllegalArgumentException("Invalid query binding ?" + position);
                query = query.where(tokens[i], tokens[i + 1], parameters == null ? "validation" : parameter(parameters, position));
            }
            return query;
        }
        private Object parameter(Object[] parameters, int position) {
            if (position >= parameters.length || parameters[position] == null || parameters[position] instanceof Pageable)
                throw new IllegalArgumentException("Invalid query binding ?" + position);
            return parameters[position];
        }
    }
}
