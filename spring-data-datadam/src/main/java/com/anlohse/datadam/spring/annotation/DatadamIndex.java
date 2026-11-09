package com.anlohse.datadam.spring.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(DatadamIndexes.class)
public @interface DatadamIndex {
    String name();
    IndexField[] fields();
    boolean unique() default false;
    Kind kind() default Kind.COMPOUND;
    enum Kind { COMPOUND, SEARCH }
}
