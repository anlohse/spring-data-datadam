package io.datadam.demo.support;

import java.io.*;
import java.util.Properties;
import io.datadam.demo.security.DisposableDatadam;

/** One image contract shared by Compose/scripts/tests. Resolve mutable local tags to an ID before running. */
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
            String image = System.getenv().getOrDefault("DATADAM_IMAGE", setting("DATADAM_IMAGE"));
            var inspection = new com.fasterxml.jackson.databind.ObjectMapper().readTree(DisposableDatadam.docker("image", "inspect", image)).get(0);
            String observed = inspection.path("Config").path("Labels").path("org.opencontainers.image.revision").asText();
            if (!revision().equals(observed)) throw new AssertionError("Datadam image revision mismatch; run scripts/build-test-image.ps1");
            serverId = inspection.path("Id").asText();
        }
        return serverId;
    }
}
