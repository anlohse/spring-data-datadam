package io.datadam.spring.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("datadam")
public class DatadamProperties {
    private String host = "localhost";
    private int port = 6379;
    private String username;
    private String password;
    private boolean ssl;
    private boolean validateConnection = true;
    private Duration timeout = Duration.ofSeconds(5);
    private Duration connectTimeout;
    private Duration readTimeout;
    private final Pool pool = new Pool();
    private final Feature health = new Feature();
    private final Feature metrics = new Feature();
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }
    public boolean isValidateConnection() { return validateConnection; }
    public void setValidateConnection(boolean validateConnection) { this.validateConnection = validateConnection; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Pool getPool() { return pool; }
    public Feature getHealth() { return health; }
    public Feature getMetrics() { return metrics; }
    public Duration effectiveConnectTimeout() { return connectTimeout == null ? timeout : connectTimeout; }
    public Duration effectiveReadTimeout() { return readTimeout == null ? timeout : readTimeout; }
    public Duration effectivePoolWait() { return pool.maxWait == null ? timeout : pool.maxWait; }
    public void validate() {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("datadam.host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("datadam.port must be between 1 and 65535");
        milliseconds(timeout, "datadam.timeout");
        milliseconds(effectiveConnectTimeout(), "datadam.connect-timeout");
        milliseconds(effectiveReadTimeout(), "datadam.read-timeout");
        milliseconds(effectivePoolWait(), "datadam.pool.max-wait");
        if (pool.maxTotal < 1 || pool.maxIdle < 0 || pool.maxIdle > pool.maxTotal || pool.minIdle < 0 || pool.minIdle > pool.maxIdle)
            throw new IllegalArgumentException("Pool requires max-total >= 1 and 0 <= min-idle <= max-idle <= max-total");
        if (pool.evictionInterval == null || pool.evictionInterval.isNegative())
            throw new IllegalArgumentException("datadam.pool.eviction-interval must be zero (disabled) or positive");
        if (!pool.evictionInterval.isZero()) milliseconds(pool.evictionInterval, "datadam.pool.eviction-interval");
    }
    private void milliseconds(Duration value, String property) {
        try {
            if (value == null || value.isNegative() || value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE)
                throw new IllegalArgumentException(property + " must be between 1ms and 2147483647ms");
        } catch (ArithmeticException e) { throw new IllegalArgumentException(property + " is too large"); }
    }
    public static class Feature {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
    public static class Pool {
        private int maxTotal = 8, maxIdle = 8, minIdle;
        private Duration maxWait;
        private boolean blockWhenExhausted = true;
        private boolean testWhileIdle;
        private Duration evictionInterval = Duration.ZERO;
        public int getMaxTotal() { return maxTotal; }
        public void setMaxTotal(int value) { maxTotal = value; }
        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int value) { maxIdle = value; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int value) { minIdle = value; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration value) { maxWait = value; }
        public boolean isBlockWhenExhausted() { return blockWhenExhausted; }
        public void setBlockWhenExhausted(boolean value) { blockWhenExhausted = value; }
        public boolean isTestWhileIdle() { return testWhileIdle; }
        public void setTestWhileIdle(boolean value) { testWhileIdle = value; }
        public Duration getEvictionInterval() { return evictionInterval; }
        public void setEvictionInterval(Duration value) { evictionInterval = value; }
    }
}
