package com.xjjk.agent.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TelemetryConfigurationContractTest {

    @Test
    void shouldExposePrometheusAndConfigureOtlpTracing() throws IOException {
        Properties properties = new Properties();
        Path productionProperties = Path.of("src", "main", "resources", "application.properties");
        assertThat(productionProperties).exists();
        try (var input = Files.newInputStream(productionProperties)) {
            properties.load(input);
        }

        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .contains("health", "info", "prometheus");
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}");
        assertThat(properties.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}");
        assertThat(properties.getProperty("management.observations.annotations.enabled"))
                .isEqualTo("true");
    }
}
