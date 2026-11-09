package com.anlohse.datadam.spring.callback;
import org.springframework.data.mapping.callback.EntityCallback;
@FunctionalInterface
public interface BeforeSaveCallback<T> extends EntityCallback<T> {
    T onBeforeSave(T entity, DatadamSaveContext context);
}
