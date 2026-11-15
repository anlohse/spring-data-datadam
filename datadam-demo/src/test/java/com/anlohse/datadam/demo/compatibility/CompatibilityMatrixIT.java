package com.anlohse.datadam.demo.compatibility;

import java.nio.file.*;
import java.util.*;
import com.anlohse.datadam.demo.security.DisposableDatadam;
import com.anlohse.datadam.demo.support.ContainerJavaClasspath;
import com.anlohse.datadam.demo.support.TestImages;
import com.anlohse.datadam.demo.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Timeout(120)
class CompatibilityMatrixIT {
    @TempDir static Path root;
    static ContainerJavaClasspath bundle;
    @BeforeAll static void bundle() throws Exception {
        bundle = ContainerJavaClasspath.prepare(root.resolve("client"),
            Path.of("..", "spring-data-datadam", "target", "test-classes"),
            Path.of("..", "datadam-spring-boot-autoconfigure", "target", "test-classes"));
    }
    @ParameterizedTest @ValueSource(ints = {17,21,25})
    void unitSuiteAndBootConsumerRunOnSupportedJvm(int version) throws Exception {
        Path reports = Path.of("target", "compatibility", "java" + version).toAbsolutePath(); Files.createDirectories(reports);
        try (var server = DisposableDatadam.plain(root.resolve("server-" + version))) {
            String client = "datadam-matrix-it-" + UUID.randomUUID();
            try {
                String image = TestImages.setting("JAVA" + version + "_IMAGE");
                DisposableDatadam.docker("create", "--name", client, "--network", "container:" + server.name, "--entrypoint", "sleep", image, "infinity");
                DisposableDatadam.docker("cp", bundle.directory().toAbsolutePath().toString(), client + ":/tmp/client");
                DisposableDatadam.docker("start", client);
                String unit = DisposableDatadam.docker("exec", client, "java", "-cp", bundle.classpath(), "org.junit.platform.console.ConsoleLauncher", "execute",
                    "--select-package=com.anlohse.datadam.spring", "--include-classname=.*Test", "--disable-ansi-colors", "--details=summary", "--fail-if-no-tests", "--reports-dir=/tmp/unit-reports");
                Files.writeString(reports.resolve("unit.log"), unit);
                assertThat(unit).containsPattern("\\[\\s*[1-9][0-9]* tests successful\\s*]").containsPattern("\\[\\s*0 tests failed\\s*]");
                String consumer = DisposableDatadam.docker("exec", client, "java", "-cp", bundle.classpath(), CompatibilityProbe.class.getName());
                Files.writeString(reports.resolve("consumer.log"), consumer);
                assertThat(consumer).contains("COMPATIBILITY_OK java=" + version, "boot=3.5.16", "springData=3.5.13");
                Files.writeString(reports.resolve("images.txt"), image + "\n" + TestImages.datadam() + "\n" + TestImages.revision() + "\n");
                DisposableDatadam.docker("cp", client + ":/tmp/unit-reports", reports.toString());
            } finally { DisposableDatadam.docker("rm", "-f", "-v", client); }
        }
    }
}
