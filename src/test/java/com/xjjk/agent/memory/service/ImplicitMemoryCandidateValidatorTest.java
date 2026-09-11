package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplicitMemoryCandidateValidatorTest {

    private final ImplicitMemoryCandidateValidator validator = new ImplicitMemoryCandidateValidator(
            new MemorySensitiveContentPolicy(new SensitiveContentSanitizer()),
            new MemoryCategoryContentPolicy(), properties(), 512, 512);

    @Test
    void canonicalizesDirectHighConfidenceEvidence() {
        ImplicitMemoryCandidate result = validator.validate(
                new ImplicitMemoryCandidate(
                        MemoryCategory.WORK_COMMON_SCOPE,
                        "work.common_scope",
                        "用户常用工作范围是Java开发",
                        "Java开发",
                        0.91),
                "我是一名Java开发");

        assertThat(result.content()).isEqualTo("用户常用工作范围是Java开发");
        assertThat(result.confidence()).isEqualTo(0.91);
    }

    @Test
    void rejectsLowConfidenceAbsentEvidenceAndWrongKey() {
        assertRejected(candidate("work.common_scope", "Java开发", 0.84), "我是一名Java开发");
        assertRejected(candidate("work.common_scope", "Java开发", 0.90), "我从事前端开发");
        assertRejected(candidate("profile.preferred_name", "Java开发", 0.90), "我是一名Java开发");
    }

    @Test
    void rejectsSensitiveAndBusinessFacts() {
        assertRejected(candidate("work.common_scope", "订单号A123456789", 0.99),
                "我的订单号A123456789");
        assertRejected(candidate("work.common_scope", "Bearer secret-token-value", 0.99),
                "Bearer secret-token-value");
    }

    private void assertRejected(ImplicitMemoryCandidate candidate, String source) {
        assertThatThrownBy(() -> validator.validate(candidate, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MEMORY_CONTENT_REJECTED");
    }

    private static ImplicitMemoryCandidate candidate(String key, String evidence, double confidence) {
        return new ImplicitMemoryCandidate(
                MemoryCategory.WORK_COMMON_SCOPE, key, evidence, evidence, confidence);
    }

    private static ImplicitMemoryProperties properties() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
