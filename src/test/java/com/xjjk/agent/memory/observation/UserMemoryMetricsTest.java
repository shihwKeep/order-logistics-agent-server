package com.xjjk.agent.memory.observation;

import com.xjjk.agent.common.api.ApiErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryMetricsTest {

    @Test
    void recordsOnlyBoundedOperationalTagsWithoutMemoryContent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UserMemoryMetrics metrics = new UserMemoryMetrics(registry);

        metrics.success("explicit_save", 1);
        metrics.success("auto_extract", 2);
        metrics.success("expiry", 3);
        metrics.failure("delete", ApiErrorCode.MEMORY_NOT_FOUND);
        metrics.indexOperation("UPSERT", "SUCCESS");
        metrics.outboxTransition("DONE");
        metrics.recall("KEYWORD_ONLY", "OK");
        metrics.directAnswer("PROGRAMMING_LANGUAGE", "ANSWERED");
        metrics.directAnswer("CURRENT_EMPLOYER", "ANSWERED");
        metrics.explicitResolution("SEMANTIC_PATH", "SAVED");
        metrics.extractionDecision("LONG_TERM");
        metrics.extractionRejection("EVIDENCE");
        metrics.verificationOutcome("SUPPORTED");
        metrics.candidateCount("INDEX", 4);
        metrics.mysqlRejected("VERSION_MISMATCH", 2);

        assertThat(registry.get("agent.user.memory.operation")
                .tags("operation", "explicit_save", "outcome", "success", "code", "NONE")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.affected")
                .tag("operation", "explicit_save").summary().totalAmount()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.affected")
                .tag("operation", "auto_extract").summary().totalAmount()).isEqualTo(2.0);
        assertThat(registry.get("agent.user.memory.affected")
                .tag("operation", "expiry").summary().totalAmount()).isEqualTo(3.0);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(tag.getKey()).doesNotContain("content", "evidence");
                    assertThat(tag.getValue()).doesNotContain("请记住", "用户偏好");
                });
        assertThat(registry.get("agent.user.memory.index.operation")
                .tags("operation", "UPSERT", "outcome", "SUCCESS")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.direct.answer")
                .tags("question", "PROGRAMMING_LANGUAGE", "outcome", "ANSWERED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.direct.answer")
                .tags("question", "CURRENT_EMPLOYER", "outcome", "ANSWERED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.explicit.resolution")
                .tags("path", "SEMANTIC_PATH", "outcome", "SAVED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.mysql.rejected")
                .tag("reason", "VERSION_MISMATCH").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("agent.user.memory.extraction.decision")
                .tag("decision", "LONG_TERM").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.extraction.rejected")
                .tag("reason", "EVIDENCE").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.user.memory.verification")
                .tag("outcome", "SUPPORTED").counter().count()).isEqualTo(1.0);
    }
}
