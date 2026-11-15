package com.anlohse.datadam.demo.support;

import java.io.*;
import java.time.Duration;
import java.util.Properties;
import com.anlohse.datadam.demo.security.DisposableDatadam;

/** Resolve the pinned Datadam image to a local ID before running the tests. */
public final class TestImages {
    private static final Properties SETTINGS = load();
    private static String serverId;
    private TestImages() {}
    private static Properties load() {
        var settings = new Properties();
        try (var input = TestImages.class.getResourceAsStream("/test-images.properties")) {
            if (input == null) throw new IllegalStateException("Missing test image contract");
            settings.load(input); return settings;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static String setting(String name) {
        String value = SETTINGS.getProperty(name);
        if (value == null) throw new IllegalArgumentException("Missing pinned image setting: " + name);
        return value;
    }
    public static String revision() { return setting("DATADAM_REVISION"); }
    public static String tlsJava() { return System.getenv().getOrDefault("DATADAM_TLS_JAVA_IMAGE", setting("DATADAM_TLS_JAVA_IMAGE")); }
    public static synchronized String datadam() throws Exception {
        if (serverId == null) {
            String override = System.getenv("DATADAM_IMAGE");
            String image = override == null || override.isBlank() ? setting("DATADAM_IMAGE") : override;
            String details;
            try {
                details = DisposableDatadam.docker("image", "inspect", image);
            } catch (AssertionError missing) {
                if (!image.equals(setting("DATADAM_IMAGE"))) throw missing;
                DisposableDatadam.run(Duration.ofMinutes(5), "docker", "pull", image);
                details = DisposableDatadam.docker("image", "inspect", image);
            }
            var inspection = new com.fasterxml.jackson.databind.ObjectMapper().readTree(details).get(0);
            String observed = inspection.path("Config").path("Labels").path("org.opencontainers.image.revision").asText();
            if (!revision().equals(observed)) throw new AssertionError("Datadam image revision mismatch: " + image);
            serverId = inspection.path("Id").asText();
        }
        return serverId;
    }
}
