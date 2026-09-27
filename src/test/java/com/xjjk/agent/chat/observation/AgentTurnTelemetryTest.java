package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnTelemetryTest {

    @Test
    void recordsBoundedMetricsAndKeepsRequestIdentityOutOfMetricTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        AtomicReference<Observation.Context> stopped = captureStopped(observations);
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(meters, observations);

        try (AgentTurnTelemetry.Turn turn = telemetry.start(
                "resumable", "request-123", "conversation-456")) {
            turn.complete("SUCCESS", "KNOWLEDGE");
        }

        assertThat(meters.counter(
                "agent.turn.completed",
                "mode", "resumable",
                "outcome", "SUCCESS",
                "intent", "KNOWLEDGE").count()).isEqualTo(1D);
        assertThat(meters.find("agent.turn.completed")
                .tagKeys("requestId", "conversationId")
                .meters()).isEmpty();

        Observation.Context context = stopped.get();
        assertThat(context.getName()).isEqualTo("agent.turn");
        assertThat(context.getLowCardinalityKeyValue("mode").getValue())
                .isEqualTo("resumable");
        assertThat(context.getLowCardinalityKeyValue("outcome").getValue())
                .isEqualTo("SUCCESS");
        assertThat(context.getLowCardinalityKeyValue("intent").getValue())
                .isEqualTo("KNOWLEDGE");
        assertThat(context.getHighCardinalityKeyValue("request.id").getValue())
                .isEqualTo("request-123");
        assertThat(context.getHighCardinalityKeyValue("conversation.id").getValue())
                .isEqualTo("conversation-456");
    }

    @Test
    void normalizesUnknownLabelsAndCompletesOnlyOnce() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(meters, observations);

        AgentTurnTelemetry.Turn turn = telemetry.start("unexpected", "request-1", null);
        turn.complete("unexpected", "unexpected");
        turn.complete("SUCCESS", "GENERAL");
        turn.close();

        assertThat(meters.counter(
                "agent.turn.completed",
                "mode", "unknown",
                "outcome", "UNKNOWN",
                "intent", "UNKNOWN").count()).isEqualTo(1D);
        assertThat(meters.timer(
                "agent.turn.duration",
                "mode", "unknown",
                "outcome", "UNKNOWN",
                "intent", "UNKNOWN").count()).isEqualTo(1L);
    }

    @Test
    void bindsTurnToWorkerThreadAndLetsFinalizerCompleteIt() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(meters, observations);

        telemetry.run("direct", "request-1", null, () ->
                telemetry.completeCurrent("TIMEOUT", "GENERAL", "conversation-1"));

        assertThat(meters.counter(
                "agent.turn.completed",
                "mode", "direct",
                "outcome", "TIMEOUT",
                "intent", "GENERAL").count()).isEqualTo(1D);
        assertThat(telemetry.hasCurrentTurn()).isFalse();
    }

    private AtomicReference<Observation.Context> captureStopped(
            ObservationRegistry registry
    ) {
        AtomicReference<Observation.Context> stopped = new AtomicReference<>();
        registry.observationConfig().observationHandler(
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
        return stopped;
    }
}
