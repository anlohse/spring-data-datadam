package com.anlohse.datadam.spring.config;

import java.util.*;

import com.anlohse.datadam.spring.repository.DatadamRepository;
import com.anlohse.datadam.spring.repository.DatadamRepositoryFactoryBean;
import com.anlohse.datadam.spring.repository.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.util.ClassUtils;

public class DatadamRepositoriesRegistrar implements ImportBeanDefinitionRegistrar {
    @Override public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        var attrs = metadata.getAnnotationAttributes(EnableDatadamRepositories.class.getName());
        Set<String> packages = new LinkedHashSet<>(List.of((String[]) attrs.get("basePackages")));
        for (Class<?> type : (Class<?>[]) attrs.get("basePackageClasses")) packages.add(ClassUtils.getPackageName(type));
        if (packages.isEmpty()) packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent() && definition.getMetadata().isInterface()
                    && !definition.getMetadata().hasAnnotation(NoRepositoryBean.class.getName());
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(DatadamRepository.class));
        for (String base : packages) for (BeanDefinition candidate : scanner.findCandidateComponents(base)) {
            String className = candidate.getBeanClassName();
            var bean = BeanDefinitionBuilder.genericBeanDefinition(DatadamRepositoryFactoryBean.class)
                .addConstructorArgValue(className).getBeanDefinition();
            // Fully qualified names avoid collisions between repository interfaces from distinct packages.
            if (!registry.containsBeanDefinition(className)) registry.registerBeanDefinition(className, bean);
        }
    }
}
