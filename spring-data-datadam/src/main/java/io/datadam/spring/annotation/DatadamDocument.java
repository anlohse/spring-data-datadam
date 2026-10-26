package io.datadam.spring.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DatadamDocument {
    String prefix();
    /** Positive TTL refreshed on save; -1 preserves any existing expiry (new documents are persistent). */
    long timeToLive() default -1;
    TimeUnit timeToLiveUnit() default TimeUnit.SECONDS;
}
