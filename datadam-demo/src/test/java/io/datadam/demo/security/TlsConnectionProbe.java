package io.datadam.demo.security;

import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import io.datadam.spring.*;
import io.datadam.spring.config.*;

/** Runs in a separate JVM so JSSE trust-store state never changes the Maven/test JVM. */
public final class TlsConnectionProbe {
    public static void main(String[] arguments) {
        if (!arguments[2].equals(System.getProperty("javax.net.ssl.trustStore"))) throw new AssertionError("Trust store override was not applied");
        var p = new DatadamProperties(); p.setHost("127.0.0.1"); p.setPort(Integer.parseInt(arguments[0]));
        p.setSsl(true); p.setUsername("client"); p.setPassword("test-client-password"); p.setValidateConnection(false);
        p.setConnectTimeout(Duration.ofSeconds(3)); p.setReadTimeout(Duration.ofSeconds(3));
        try (var pool = new DatadamAutoConfiguration().datadamPool(p)) {
            try {
                var commands = new DatadamCommands(pool);
                if (!"PONG".equals(commands.execute("PING"))) throw new AssertionError("Unexpected TLS response");
                if (!arguments[1].equals("accept")) throw new AssertionError("Certificate was accepted unexpectedly");
                System.out.println("TLS_ACCEPTED");
            } catch (org.springframework.dao.DataAccessException error) {
                if (!arguments[1].equals("reject")) throw new AssertionError("Trusted TLS connection failed", error);
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SSLHandshakeException) { System.out.println("TLS_REJECTED_HANDSHAKE"); return; }
                }
                throw new AssertionError("Expected certificate handshake failure", error);
            }
        }
    }
}
