package io.datadam.spring.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface IndexField {
    String path();
    String alias();
    Type type() default Type.TAG;
    boolean caseSensitive() default false;
    /** Empty uses the server default: comma for FT HASH TAG; no splitting otherwise. One ASCII byte when explicit. */
    String separator() default "";
    enum Type { TAG, NUMERIC }
}
