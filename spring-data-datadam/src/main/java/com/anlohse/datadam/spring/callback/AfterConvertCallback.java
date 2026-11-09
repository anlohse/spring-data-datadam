package com.anlohse.datadam.spring.callback;
import org.springframework.data.mapping.callback.EntityCallback;
@FunctionalInterface
public interface AfterConvertCallback<T> extends EntityCallback<T> {
    T onAfterConvert(T entity, String key);
}
