package com.anlohse.datadam.spring.callback;
import org.springframework.data.mapping.callback.EntityCallback;
@FunctionalInterface
public interface BeforeConvertCallback<T> extends EntityCallback<T> {
    T onBeforeConvert(T entity, DatadamSaveContext context);
}
