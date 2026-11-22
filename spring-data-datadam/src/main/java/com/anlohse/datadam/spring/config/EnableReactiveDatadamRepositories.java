package com.anlohse.datadam.spring.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(ReactiveDatadamRepositoriesRegistrar.class)
public @interface EnableReactiveDatadamRepositories {
    String[] basePackages() default {};
    Class<?>[] basePackageClasses() default {};
}
