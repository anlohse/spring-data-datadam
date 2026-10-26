package io.datadam.spring.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DatadamIndexes { DatadamIndex[] value(); }
