package io.datadam.spring.config;

import java.lang.annotation.*;
import org.springframework.context.annotation.Import;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(DatadamRepositoriesRegistrar.class)
public @interface EnableDatadamRepositories {
    String[] basePackages() default {};
    Class<?>[] basePackageClasses() default {};
}
