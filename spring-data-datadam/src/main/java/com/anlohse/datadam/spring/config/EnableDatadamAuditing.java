package com.anlohse.datadam.spring.config;

import java.lang.annotation.*;
import org.springframework.context.annotation.Import;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(DatadamAuditingConfiguration.class)
public @interface EnableDatadamAuditing {
    String auditorAwareRef() default "";
    String dateTimeProviderRef() default "";
    boolean modifyOnCreate() default true;
    boolean setDates() default true;
}
