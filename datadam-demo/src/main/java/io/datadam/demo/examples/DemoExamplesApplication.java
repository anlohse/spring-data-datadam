package io.datadam.demo.examples;

import io.datadam.demo.DemoApplication;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Run through scripts/run-demo-examples.ps1, with the web application and its credentials unchanged. */
public final class DemoExamplesApplication {
    private DemoExamplesApplication() {}
    public static void main(String[] args) throws Exception {
        String scenario = System.getProperty("examples.scenario", "all");
        int volume = Integer.parseInt(System.getProperty("examples.volume", "30"));
        if (!scenario.equals("all") && !ExampleScenarios.SCENARIOS.contains(scenario))
            throw new IllegalArgumentException("Unknown scenario: " + scenario);
        try (var context = new SpringApplicationBuilder(DemoApplication.class).web(WebApplicationType.NONE).run(args)) {
            var runner = context.getBean(ExampleScenarios.class);
            var mapper = context.getBean(ObjectMapper.class);
            for (String code : scenario.equals("all") ? ExampleScenarios.SCENARIOS : java.util.List.of(scenario)) {
                var report = runner.run(code, volume);
                System.out.println("EXAMPLE_RESULT " + mapper.writeValueAsString(report));
            }
        }
    }
}
