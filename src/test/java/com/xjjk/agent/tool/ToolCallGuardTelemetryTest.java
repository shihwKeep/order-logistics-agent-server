package com.xjjk.agent.tool;

import com.xjjk.agent.tool.observation.ToolCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallGuardTelemetryTest {

    @Test
    void observesOnlyTheFirstRealExecutionAndRecordsReuse() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallGuard guard = guard(3, meters);
        AtomicInteger executions = new AtomicInteger();

        assertThat(guard.execute("search_orders", "AUTO|O1", () -> {
            executions.incrementAndGet();
            return "result";
        })).isEqualTo("result");
        assertThat(guard.execute("search_orders", "AUTO|O1", () -> {
            executions.incrementAndGet();
            return "unexpected";
        })).isEqualTo("result");

        assertThat(executions).hasValue(1);
        assertThat(counter(meters, "FIRST")).isEqualTo(1D);
        assertThat(counter(meters, "REUSED")).isEqualTo(1D);
        assertThat(meters.timer(
                "agent.tool.call.duration",
                "tool", "search_orders",
                "outcome", "SUCCESS").count()).isEqualTo(1L);
    }

    @Test
    void observesLimitRejectionAndFailedCallQuotaRelease() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallGuard guard = guard(1, meters);

        assertThatThrownBy(() -> guard.execute(
                "search_orders", "AUTO|O1", () -> {
                    throw new IllegalStateException("temporary failure");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(guard.execute(
                "search_orders", "AUTO|O1", () -> "recovered"))
                .isEqualTo("recovered");
        assertThatThrownBy(() -> guard.execute(
                "search_orders", "AUTO|O2", () -> "rejected"))
                .isInstanceOf(ToolCallLimitExceededException.class);

        assertThat(counter(meters, "FIRST")).isEqualTo(2D);
        assertThat(counter(meters, "FAILED_RELEASED")).isEqualTo(1D);
        assertThat(counter(meters, "LIMIT_EXCEEDED")).isEqualTo(1D);
        assertThat(meters.timer(
                "agent.tool.call.duration",
                "tool", "search_orders",
                "outcome", "FAILURE").count()).isEqualTo(1L);
    }

    private ToolCallGuard guard(int limit, SimpleMeterRegistry meters) {
        return new ToolCallGuard(limit, new ToolCallMetrics(
                meters, ObservationRegistry.create()));
    }

    private double counter(SimpleMeterRegistry meters, String decision) {
        return meters.counter(
                "agent.tool.guard",
                "tool", "search_orders",
                "decision", decision).count();
    }
}
