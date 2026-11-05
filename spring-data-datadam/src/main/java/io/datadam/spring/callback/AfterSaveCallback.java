package io.datadam.spring.callback;
import org.springframework.data.mapping.callback.EntityCallback;
/** Runs after acknowledgement. Changes to the returned entity are not automatically written again. */
@FunctionalInterface
public interface AfterSaveCallback<T> extends EntityCallback<T> {
    T onAfterSave(T entity, DatadamSaveContext context);
}
