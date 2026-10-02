package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeQueryMetricsTest {

    @Test
    void recordsLowCardinalityGraphNodeAndResultMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CompositeQueryMetrics metrics = new CompositeQueryMetrics(
                meters, ObservationRegistry.create());

        assertThat(metrics.node("knowledge.query", "request-1", () -> "ok"))
                .isEqualTo("ok");
        metrics.graph("SUCCESS");
        metrics.result("knowledge-citations", "SUCCESS");
        metrics.result("not-allowed", "not-allowed");

        assertThat(meters.timer(
                "agent.composite.node",
                "graph", "composite-v1",
                "node", "knowledge.query",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
        assertThat(meters.counter(
                "agent.composite.graph",
                "graph", "composite-v1",
                "outcome", "SUCCESS").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.composite.result",
                "kind", "knowledge-citations",
                "outcome", "SUCCESS").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.composite.result",
                "kind", "unknown",
                "outcome", "FAILURE").count()).isEqualTo(1D);
        assertThat(meters.find("agent.composite.graph")
                .tagKeys("requestId", "conversationId", "orderCode")
                .meters()).isEmpty();
    }

    @Test
    void keepsObservationAndTimerMetersCompatibleWhenNodeFails() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        CompositeQueryMetrics metrics = new CompositeQueryMetrics(meters, observations);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                metrics.node("business.query", "request-failed", () -> {
                    throw new IllegalStateException("downstream unavailable");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(meters.timer(
                "agent.composite.node",
                "graph", "composite-v1",
                "node", "business.query",
                "outcome", "ERROR").count()).isEqualTo(1L);
    }
}
