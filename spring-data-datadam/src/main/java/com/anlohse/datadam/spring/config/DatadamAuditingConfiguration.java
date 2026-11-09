package com.anlohse.datadam.spring.config;

import java.time.*;
import java.util.*;
import com.anlohse.datadam.spring.auditing.DatadamAuditing;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;

@Configuration(proxyBeanMethods = false)
public class DatadamAuditingConfiguration implements ImportAware {
    private Map<String, Object> options;
    @Override public void setImportMetadata(AnnotationMetadata metadata) {
        options = metadata.getAnnotationAttributes(EnableDatadamAuditing.class.getName());
    }
    @Bean public DatadamAuditing datadamAuditing(BeanFactory beans) {
        String auditorRef = (String) options.get("auditorAwareRef");
        String timeRef = (String) options.get("dateTimeProviderRef");
        AuditorAware<?> auditor = auditorRef.isEmpty() ? beans.getBeanProvider(AuditorAware.class).getIfAvailable()
            : beans.getBean(auditorRef, AuditorAware.class);
        DateTimeProvider provider = timeRef.isEmpty() ? beans.getBeanProvider(DateTimeProvider.class).getIfAvailable()
            : beans.getBean(timeRef, DateTimeProvider.class);
        if (provider == null && !(boolean) options.get("setDates")) provider = Optional::empty;
        if (provider == null) {
            Clock clock = beans.getBeanProvider(Clock.class).getIfAvailable(Clock::systemUTC);
            provider = () -> Optional.of(Instant.now(clock));
        }
        return new DatadamAuditing(provider, auditor, (boolean) options.get("modifyOnCreate"), (boolean) options.get("setDates"));
    }
}
