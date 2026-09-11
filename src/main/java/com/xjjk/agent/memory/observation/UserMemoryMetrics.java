package com.xjjk.agent.memory.observation;

import com.xjjk.agent.common.api.ApiErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 仅记录低基数运行状态，不接收也不记录记忆正文或证据。 */
@Component
public class UserMemoryMetrics {

    private static final Set<String> OPERATIONS = Set.of(
            "explicit_save", "edit", "delete", "clear_explicit", "clear_all",
            "auto_extract", "expiry");
    private final MeterRegistry registry;

    public UserMemoryMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void success(String operation, int affectedCount) {
        record(operation, "success", "NONE");
        if (affectedCount >= 0) {
            registry.summary("agent.user.memory.affected", "operation", requireOperation(operation))
                    .record(affectedCount);
        }
    }

    public void rejected(String operation, ApiErrorCode code) {
        record(operation, "rejected", requireCode(code));
    }

    public void failure(String operation, ApiErrorCode code) {
        record(operation, "failure", requireCode(code));
    }

    private void record(String operation, String outcome, String code) {
        registry.counter("agent.user.memory.operation",
                "operation", requireOperation(operation),
                "outcome", outcome,
                "code", code).increment();
    }

    private static String requireOperation(String operation) {
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("未知用户记忆指标操作");
        }
        return operation;
    }

    private static String requireCode(ApiErrorCode code) {
        return Objects.requireNonNull(code, "code").code();
    }
}
