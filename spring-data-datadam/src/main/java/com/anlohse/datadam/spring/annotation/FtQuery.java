package com.anlohse.datadam.spring.annotation;
import java.lang.annotation.*;
/** Bind ?0, ?1 as $p0, $p1 through FT.SEARCH PARAMS; never interpolate values. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FtQuery {
    String index();
    String value();
}
