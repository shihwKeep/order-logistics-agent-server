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
        metrics.branch("business:logistics-timeline", "SUCCESS");
        metrics.checkpoint("load", "RESTORED");
        metrics.retry("business.query", "SCHEDULED");

        assertThat(meters.timer(
                "agent.composite.node",
                "graph", "composite-v2",
                "node", "knowledge.query",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
        assertThat(meters.counter(
                "agent.composite.graph",
                "graph", "composite-v2",
                "outcome", "SUCCESS").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.composite.result",
                "kind", "knowledge-citations",
                "outcome", "SUCCESS").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.composite.result",
                "kind", "unknown",
                "outcome", "FAILURE").count()).isEqualTo(1D);
        assertThat(meters.counter("agent.composite.branch",
                "graph", "composite-v2",
                "branch", "business:logistics-timeline",
                "outcome", "SUCCESS").count()).isEqualTo(1D);
        assertThat(meters.counter("agent.composite.checkpoint",
                "graph", "composite-v2",
                "operation", "load",
                "outcome", "RESTORED").count()).isEqualTo(1D);
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
                "graph", "composite-v2",
                "node", "business.query",
                "outcome", "ERROR").count()).isEqualTo(1L);
    }

    @Test
    void recordsBoundedDependencyPartialAndResumeMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CompositeQueryMetrics metrics = new CompositeQueryMetrics(
                meters, ObservationRegistry.create());

        metrics.dependency("LATEST_ORDER", "RESOLVED");
        metrics.dependency("unbounded-value", "unbounded-value");
        metrics.partialSuccess();
        metrics.checkpointResume("success");

        assertThat(meters.counter("agent.composite.dependency",
                "graph", "composite-v2", "type", "LATEST_ORDER",
                "outcome", "RESOLVED").count()).isEqualTo(1D);
        assertThat(meters.counter("agent.composite.dependency",
                "graph", "composite-v2", "type", "LATEST_ORDER",
                "outcome", "FAILED").count()).isEqualTo(1D);
        assertThat(meters.counter("agent.composite.partial_success",
                "graph", "composite-v2").count()).isEqualTo(1D);
        assertThat(meters.counter("agent.composite.checkpoint.resume",
                "graph", "composite-v2", "outcome", "SUCCESS").count())
                .isEqualTo(1D);
    }
}
