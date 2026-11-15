package com.anlohse.datadam.spring.repository;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import com.anlohse.datadam.spring.DatadamTemplate;
import com.anlohse.datadam.spring.annotation.FtQuery;
import com.anlohse.datadam.spring.annotation.IdxQuery;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata;

/** Read-only, local diagnostics using exactly the same compiler as repository execution. */
public final class DatadamQueryDiagnostics {
    private final DatadamTemplate template;
    public DatadamQueryDiagnostics(DatadamTemplate template) { this.template = Objects.requireNonNull(template); }
    public DerivedQueryPlan explain(Class<?> repository, Method method) { return explain(repository, method, Sort.unsorted()); }
    /** dynamicSort is combined with OrderBy exactly as Pageable.sort/Sort at execution time. */
    public DerivedQueryPlan explain(Class<?> repository, Method method, Sort dynamicSort) {
        Objects.requireNonNull(method); Objects.requireNonNull(dynamicSort);
        var metadata = new DefaultRepositoryMetadata(repository);
        if (!method.getDeclaringClass().isAssignableFrom(repository) || method.isDefault() || Modifier.isStatic(method.getModifiers())
            || method.isAnnotationPresent(IdxQuery.class) || method.isAnnotationPresent(FtQuery.class)
            || method.getDeclaringClass().getName().startsWith("org.springframework.data.")
            || method.getDeclaringClass() == DatadamRepository.class
            || metadata.getCrudMethods().getFindOneMethod().filter(method::equals).isPresent())
            throw new IllegalArgumentException("Expected a derived repository query method: " + method.getName());
        return new DerivedDatadamQuery(method, metadata, new SpelAwareProxyProjectionFactory(), template).explain(dynamicSort);
    }
}
