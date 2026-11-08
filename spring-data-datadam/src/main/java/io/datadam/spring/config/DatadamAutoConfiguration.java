package io.datadam.spring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import redis.clients.jedis.*;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

@AutoConfiguration
@EnableConfigurationProperties(DatadamProperties.class)
public class DatadamAutoConfiguration {
    @Bean @ConditionalOnMissingBean(name = "datadamIdGenerator")
    public DatadamIdGenerator datadamIdGenerator() { return new UuidDatadamIdGenerator(); }
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "datadamPool")
    public JedisPool datadamPool(DatadamProperties p) {
        p.validate();
        var config = DefaultJedisClientConfig.builder().database(0).ssl(p.isSsl())
            .connectionTimeoutMillis(Math.toIntExact(p.effectiveConnectTimeout().toMillis()))
            .socketTimeoutMillis(Math.toIntExact(p.effectiveReadTimeout().toMillis()));
        if (p.isSsl()) {
            var parameters = new javax.net.ssl.SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            config.sslParameters(parameters);
        }
        if (p.getUsername() != null) config.user(p.getUsername());
        if (p.getPassword() != null) config.password(p.getPassword());
        var poolConfig = new GenericObjectPoolConfig<Jedis>();
        // Discard idle connections closed by a server restart before submitting a business command.
        poolConfig.setTestOnBorrow(p.isValidateConnection());
        poolConfig.setMaxWait(p.effectivePoolWait());
        poolConfig.setMaxTotal(p.getPool().getMaxTotal());
        poolConfig.setMaxIdle(p.getPool().getMaxIdle());
        poolConfig.setMinIdle(p.getPool().getMinIdle());
        poolConfig.setBlockWhenExhausted(p.getPool().isBlockWhenExhausted());
        poolConfig.setTestWhileIdle(p.getPool().isTestWhileIdle());
        poolConfig.setTimeBetweenEvictionRuns(p.getPool().getEvictionInterval().isZero() ? java.time.Duration.ofMillis(-1) : p.getPool().getEvictionInterval());
        return new JedisPool(poolConfig, new DatadamJedisFactory(new HostAndPort(p.getHost(), p.getPort()), config.build()));
    }
    @Bean @ConditionalOnMissingBean
    public DatadamCommands datadamCommands(@org.springframework.beans.factory.annotation.Qualifier("datadamPool") JedisPool pool,
        ObjectProvider<io.datadam.spring.diagnostics.DatadamCommandObserver> observer) {
        return new DatadamCommands(pool, observer.getIfAvailable(() -> io.datadam.spring.diagnostics.DatadamCommandObserver.NONE));
    }
    @Bean @ConditionalOnMissingBean
    public io.datadam.spring.repository.DatadamQueryDiagnostics datadamQueryDiagnostics(DatadamTemplate template) {
        return new io.datadam.spring.repository.DatadamQueryDiagnostics(template);
    }
    @Bean @ConditionalOnMissingBean
    public DatadamTemplate datadamTemplate(DatadamCommands commands, ObjectProvider<ObjectMapper> mapper,
        java.util.Map<String, DatadamIdGenerator> idGenerators, org.springframework.beans.factory.BeanFactory beans,
        ObjectProvider<io.datadam.spring.auditing.DatadamAuditing> auditing) {
        return new DatadamTemplate(commands, mapper.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()), idGenerators,
            org.springframework.data.mapping.callback.EntityCallbacks.create(beans), auditing.getIfAvailable(), type -> hasWriteCallbacks(beans, type));
    }
    private static boolean hasWriteCallbacks(org.springframework.beans.factory.BeanFactory beans, Class<?> entityType) {
        if (!(beans instanceof org.springframework.beans.factory.ListableBeanFactory listable)) return true;
        for (Class<?> callbackType : java.util.List.of(io.datadam.spring.callback.BeforeConvertCallback.class,
            io.datadam.spring.callback.BeforeSaveCallback.class, io.datadam.spring.callback.AfterSaveCallback.class)) {
            for (var callback : org.springframework.beans.factory.BeanFactoryUtils.beansOfTypeIncludingAncestors(listable, callbackType).values()) {
                Class<?> target = org.springframework.core.ResolvableType.forClass(org.springframework.aop.support.AopUtils.getTargetClass(callback))
                    .as(callbackType).resolveGeneric(0);
                if (target == null || target.isAssignableFrom(entityType) || entityType.isAssignableFrom(target)) return true;
            }
        }
        return false;
    }
}
