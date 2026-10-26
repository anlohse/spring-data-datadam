package io.datadam.spring.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface IndexField {
    String path();
    String alias();
    Type type() default Type.TAG;
    boolean caseSensitive() default false;
    enum Type { TAG, NUMERIC }
}
