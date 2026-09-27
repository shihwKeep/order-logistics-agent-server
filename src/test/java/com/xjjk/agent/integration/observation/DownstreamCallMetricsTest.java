package com.xjjk.agent.integration.observation;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamCallMetricsTest {

    @Test
    void recordsSuccessfulLogicalCallWithBoundedDimensions() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DownstreamCallMetrics metrics = metrics(meters);

        assertThat(metrics.observe(
                "order", "search", () -> "ok")).isEqualTo("ok");

        assertThat(meters.timer(
                "agent.downstream.call.duration",
                "service", "order",
                "operation", "search",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
    }

    @Test
    void classifiesTimeoutAndRetryAttemptWithoutLeakingDynamicValues() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DownstreamCallMetrics metrics = metrics(meters);
        RuntimeException timeout = new RuntimeException(
                "contains-sensitive-value", new SocketTimeoutException("timeout"));

        assertThatThrownBy(() -> metrics.observe(
                "user-service", "user-operation", () -> {
                    throw timeout;
                })).isSameAs(timeout);
        metrics.attempt("order", "search", 1, timeout, true);

        assertThat(meters.timer(
                "agent.downstream.call.duration",
                "service", "unknown",
                "operation", "unknown",
                "outcome", "READ_TIMEOUT").count()).isEqualTo(1L);
        assertThat(meters.counter(
                "agent.downstream.attempt",
                "service", "order",
                "operation", "search",
                "attempt", "1",
                "outcome", "READ_TIMEOUT",
                "retry", "true").count()).isEqualTo(1D);
    }

    @Test
    void distinguishesRemoteRejectionAndServerFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DownstreamCallMetrics metrics = metrics(meters);

        metrics.attempt("knowledge", "retrieve", 1, feign(403), false);
        metrics.attempt("knowledge", "retrieve", 1, feign(503), true);

        assertThat(counter(meters, "REMOTE_REJECTED", "false")).isEqualTo(1D);
        assertThat(counter(meters, "SERVER_ERROR", "true")).isEqualTo(1D);
    }

    private DownstreamCallMetrics metrics(SimpleMeterRegistry meters) {
        return new DownstreamCallMetrics(meters, ObservationRegistry.create());
    }

    private RuntimeException feign(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET, "/internal", Map.of(), null, null, null);
        return FeignException.errorStatus(
                "KnowledgeClient#retrieve",
                Response.builder()
                        .status(status)
                        .reason("failure")
                        .request(request)
                        .headers(Map.of())
                        .build());
    }

    private double counter(
            SimpleMeterRegistry meters,
            String outcome,
            String retry) {
        return meters.counter(
                "agent.downstream.attempt",
                "service", "knowledge",
                "operation", "retrieve",
                "attempt", "1",
                "outcome", outcome,
                "retry", retry).count();
    }
}
