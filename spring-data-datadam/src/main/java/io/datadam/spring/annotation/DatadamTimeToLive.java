package io.datadam.spring.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * Positive TTL requested on save. A nullable field inherits the document's default when null.
 * Supports integral primitives/wrappers and Duration. Duration carries its own unit.
 * The field is ordinary JSON data: reads return the saved policy, not a remaining-time countdown.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DatadamTimeToLive {
    TimeUnit unit() default TimeUnit.SECONDS;
}
