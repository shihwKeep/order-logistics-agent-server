package com.xjjk.agent.tool.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallMetricsTest {

    @Test
    void tracesRealExecutionAndBoundsToolName() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        AtomicReference<Observation.Context> stopped = new AtomicReference<>();
        observations.observationConfig().observationHandler(
                new ObservationHandler<Observation.Context>() {
                    @Override
                    public void onStop(Observation.Context context) {
                        stopped.set(context);
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }
                });
        ToolCallMetrics metrics = new ToolCallMetrics(meters, observations);

        String value = metrics.execute("search_products", () -> "ok");

        assertThat(value).isEqualTo("ok");
        assertThat(stopped.get().getName()).isEqualTo("agent.tool.call");
        assertThat(stopped.get().getLowCardinalityKeyValue("tool").getValue())
                .isEqualTo("search_products");
        assertThat(stopped.get().getLowCardinalityKeyValue("outcome").getValue())
                .isEqualTo("SUCCESS");
        assertThat(meters.timer(
                "agent.tool.call.duration",
                "tool", "search_products",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
    }

    @Test
    void recordsFailureAndDoesNotExposeUnknownToolName() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallMetrics metrics = new ToolCallMetrics(
                meters, ObservationRegistry.create());

        assertThatThrownBy(() -> metrics.execute("dynamic-user-value", () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meters.timer(
                "agent.tool.call.duration",
                "tool", "unknown",
                "outcome", "FAILURE").count()).isEqualTo(1L);
    }

    @Test
    void recordsGuardDecisionsWithBoundedLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallMetrics metrics = new ToolCallMetrics(
                meters, ObservationRegistry.create());

        metrics.guard("search_orders", "REUSED");
        metrics.guard("search_orders", "LIMIT_EXCEEDED");

        assertThat(meters.counter(
                "agent.tool.guard",
                "tool", "search_orders",
                "decision", "REUSED").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.tool.guard",
                "tool", "search_orders",
                "decision", "LIMIT_EXCEEDED").count()).isEqualTo(1D);
    }

    @Test
    void recordsStructuredResultLifecycleWithBoundedLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallMetrics metrics = new ToolCallMetrics(
                meters, ObservationRegistry.create());

        metrics.result("knowledge-citations", "STAGED");
        metrics.result("user-controlled-kind", "user-controlled-outcome");

        assertThat(meters.counter(
                "agent.tool.result",
                "kind", "knowledge-citations",
                "outcome", "STAGED").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.tool.result",
                "kind", "unknown",
                "outcome", "UNKNOWN").count()).isEqualTo(1D);
    }
}
