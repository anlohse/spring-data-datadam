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
}
