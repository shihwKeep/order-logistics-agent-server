package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    void recordsChildStageWithBoundedOutcome() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        CopyOnWriteArrayList<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        observations.observationConfig().observationHandler(
                new ObservationHandler<Observation.Context>() {
                    @Override
                    public void onStop(Observation.Context context) {
                        stopped.add(context);
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }
                });
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(meters, observations);

        telemetry.run("direct", "request-1", null, () -> {
            String value = telemetry.observeStage("context.load", () -> "loaded");
            assertThat(value).isEqualTo("loaded");
            telemetry.completeCurrent("SUCCESS", "GENERAL", "conversation-1");
        });

        assertThat(stopped).extracting(Observation.Context::getName)
                .containsExactly("agent.turn.context.load", "agent.turn");
        Observation.Context child = stopped.getFirst();
        assertThat(child.getParentObservation()).isNotNull();
        assertThat(child.getLowCardinalityKeyValue("stage").getValue())
                .isEqualTo("context.load");
        assertThat(child.getLowCardinalityKeyValue("outcome").getValue())
                .isEqualTo("SUCCESS");
        assertThat(meters.timer(
                "agent.turn.stage.duration",
                "stage", "context.load",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
    }

    @Test
    void recordsStageFailureAndRethrowsOriginalException() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(
                meters, ObservationRegistry.create());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                telemetry.run("direct", "request-1", null, () ->
                        telemetry.observeStage("intent.route", () -> {
                            throw new IllegalStateException("boom");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(meters.timer(
                "agent.turn.stage.duration",
                "stage", "intent.route",
                "outcome", "ERROR").count()).isEqualTo(1L);
    }

    @Test
    void recordsModelUsageWithoutIdentityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(
                meters, ObservationRegistry.create());

        telemetry.recordModelMetrics(new ChatCallMetrics(
                "request-1", "conversation-1", "prompt-v1", "qwen-plus",
                120L, 30L, 150L, 80L, 300L, "SUCCESS", "stop"));

        assertThat(meters.summary(
                "agent.model.tokens", "direction", "input").totalAmount())
                .isEqualTo(120D);
        assertThat(meters.summary(
                "agent.model.tokens", "direction", "output").totalAmount())
                .isEqualTo(30D);
        assertThat(meters.timer(
                "agent.model.first.delta", "model_family", "qwen").count())
                .isEqualTo(1L);
        assertThat(meters.counter(
                "agent.model.completion",
                "model_family", "qwen",
                "outcome", "SUCCESS",
                "finish_reason", "STOP").count()).isEqualTo(1D);
        assertThat(meters.find("agent.model.completion")
                .tagKeys("requestId", "conversationId", "promptVersion")
                .meters()).isEmpty();
    }

    @Test
    void recordsModelStreamRetryOutcomeWithLowCardinalityLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentTurnTelemetry telemetry = new AgentTurnTelemetry(
                meters, ObservationRegistry.create());

        telemetry.recordModelRetry("scheduled");
        telemetry.recordModelRetry("exhausted");
        telemetry.recordModelRetry("unexpected");

        assertThat(meters.counter(
                "agent.model.stream.retry", "outcome", "SCHEDULED").count())
                .isEqualTo(1D);
        assertThat(meters.counter(
                "agent.model.stream.retry", "outcome", "EXHAUSTED").count())
                .isEqualTo(1D);
        assertThat(meters.counter(
                "agent.model.stream.retry", "outcome", "UNKNOWN").count())
                .isEqualTo(1D);
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
