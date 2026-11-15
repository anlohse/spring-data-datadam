package com.anlohse.datadam.spring.annotation;
import java.lang.annotation.*;
/** Triples separated by whitespace: city = ?0 age >= ?1. Values are bound as RESP arguments. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IdxQuery {
    String index();
    String value() default "";
    boolean descending() default false;
}
