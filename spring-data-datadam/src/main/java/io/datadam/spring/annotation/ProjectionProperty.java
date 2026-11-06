package io.datadam.spring.annotation;
import java.lang.annotation.*;
/** Java root property of the source entity; independent of native index and Jackson aliases. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT,ElementType.METHOD})
public @interface ProjectionProperty { String value(); }
