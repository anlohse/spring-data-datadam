package io.datadam.spring.annotation;

import java.lang.annotation.*;

/** Generate an absent @Id on save. An existing ID is always preserved. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DatadamGeneratedId {
    /** Bean name; empty selects datadamIdGenerator (UUID by default). */
    String generator() default "";
}
