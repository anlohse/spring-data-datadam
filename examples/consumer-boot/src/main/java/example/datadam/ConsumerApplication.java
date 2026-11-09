package example.datadam;

import com.anlohse.datadam.spring.config.EnableDatadamRepositories;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
@EnableDatadamRepositories(basePackageClasses = BookRepository.class)
public class ConsumerApplication {
    public static void main(String[] args) {
        verifyInstalledVersion();
        try (var context = new SpringApplicationBuilder(ConsumerApplication.class).web(WebApplicationType.NONE).run(args)) {
            if (!"PONG".equals(context.getBean(com.anlohse.datadam.spring.DatadamCommands.class).execute("PING")))
                throw new IllegalStateException("Consumer health check failed");
            System.out.println("CONSUMER_HEALTH_OK ping=PONG");
            context.getBean(ConsumerFlow.class).run();
        }
    }

    private static void verifyInstalledVersion() {
        String expected = System.getProperty("consumer.expected-version");
        String observed = null;
        for (String artifact : java.util.List.of("spring-data-datadam", "datadam-spring-boot-autoconfigure", "datadam-spring-boot-starter")) {
            String path = "/META-INF/maven/com.anlohse.datadam/" + artifact + "/pom.properties";
            try (var input = ConsumerApplication.class.getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Missing installed artifact metadata: " + artifact);
                var properties = new java.util.Properties(); properties.load(input);
                String version = properties.getProperty("version");
                if (!"com.anlohse.datadam".equals(properties.getProperty("groupId")) || version == null || (expected != null && !expected.equals(version)) || (observed != null && !observed.equals(version)))
                    throw new IllegalStateException("Consumer artifact version mismatch: " + artifact);
                observed = version;
            } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read installed artifact metadata", failure); }
        }
        System.out.println("CONSUMER_VERSION " + observed);
    }
}
