package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplicitMemoryCandidateValidatorTest {

    private final ImplicitMemoryCandidateValidator validator = new ImplicitMemoryCandidateValidator(
            new MemorySensitiveContentPolicy(new SensitiveContentSanitizer()),
            new MemoryCategoryContentPolicy(), properties(), 512, 512);

    private final ImplicitMemoryCandidateValidator semanticValidator =
            new ImplicitMemoryCandidateValidator(
                    new MemorySensitiveContentPolicy(new SensitiveContentSanitizer()),
                    new MemorySchemaRegistry(new com.fasterxml.jackson.databind.ObjectMapper()),
                    properties(), 512, 512);

    @Test
    void acceptsSemanticFactsAcrossNaturalSentenceForms() {
        assertThat(validateProgrammingLanguage(
                "我平时用 Java 语言进行开发", "Java", "Java")
                .canonicalContent()).isEqualTo("用户主要使用 Java 进行开发");
        assertThat(validateProgrammingLanguage(
                "Java 是我主要使用的开发语言", "Java", "Java")
                .canonicalContent()).isEqualTo("用户主要使用 Java 进行开发");
        assertThat(validateProgrammingLanguage(
                "平常写项目时我更习惯用 Java", "Java", "Java")
                .canonicalContent()).isEqualTo("用户主要使用 Java 进行开发");
    }

    @Test
    void usesKnownPredicateAsSchemaAuthorityWhenModelMisclassifiesMemoryType() {
        String source = "我平时主要使用 Elixir 开发。";
        MemoryFactCandidate modelCandidate = new MemoryFactCandidate(
                MemoryType.PROFILE,
                "primary_programming_language",
                "Elixir",
                "Elixir",
                source,
                MemoryStability.STABLE,
                0.95);

        var result = semanticValidator.validate(modelCandidate, source);

        assertThat(result.candidate().memoryType()).isEqualTo(MemoryType.WORK_CONTEXT);
        assertThat(result.candidate().predicate()).isEqualTo("primary_programming_language");
        assertThat(result.canonicalContent()).isEqualTo("用户主要使用 Elixir 进行开发");
        assertThat(result.verificationMethod()).isEqualTo("SEMANTIC_REQUIRED");
    }

    @Test
    void acceptsSemanticallyExtractedCurrentEmployerForIndependentVerification() {
        String source = "我在享佳工作";
        MemoryFactCandidate candidate = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "current_employer",
                "享佳",
                "享佳",
                source,
                MemoryStability.STABLE,
                0.96);

        var result = semanticValidator.validate(candidate, source);

        assertThat(result.canonicalKey()).isEqualTo("work.current_employer");
        assertThat(result.canonicalContent()).isEqualTo("用户当前工作单位是享佳");
        assertThat(result.verificationMethod()).isEqualTo("SEMANTIC_REQUIRED");
    }

    @Test
    void rejectsUngroundedFactsWithSpecificSafeReasons() {
        assertSemanticRejected(programmingLanguage(
                        "Python", "Java", "我平时用 Java 开发", 0.96),
                "我平时用 Java 开发",
                MemoryCandidateValidationException.Reason.UNSUPPORTED);
        assertSemanticRejected(programmingLanguage(
                        "Java", "Java", "我平时用 Java 开发", 0.50),
                "我平时用 Java 开发",
                MemoryCandidateValidationException.Reason.CONFIDENCE);
        assertSemanticRejected(programmingLanguage(
                        "Java", "Java", "另一句话", 0.96),
                "我平时用 Java 开发",
                MemoryCandidateValidationException.Reason.EVIDENCE);
    }

    @Test
    void rejectsSensitiveOrInstructionLikeSemanticFacts() {
        MemoryFactCandidate sensitive = new MemoryFactCandidate(
                MemoryType.STABLE_USER_FACT,
                "fact",
                "我的手机号是13800138000",
                "我的手机号是13800138000",
                "我的手机号是13800138000",
                MemoryStability.STABLE,
                0.99);
        assertSemanticRejected(sensitive, sensitive.evidenceText(),
                MemoryCandidateValidationException.Reason.SENSITIVE);
    }

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

    private com.xjjk.agent.memory.domain.ValidatedMemoryFact validateProgrammingLanguage(
            String source,
            String value,
            String valueEvidence) {
        return semanticValidator.validate(programmingLanguage(
                value, valueEvidence, source, 0.96), source);
    }

    private static MemoryFactCandidate programmingLanguage(
            String value,
            String valueEvidence,
            String evidence,
            double confidence) {
        return new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "primary_programming_language",
                value,
                valueEvidence,
                evidence,
                MemoryStability.STABLE,
                confidence);
    }

    private void assertSemanticRejected(
            MemoryFactCandidate candidate,
            String source,
            MemoryCandidateValidationException.Reason reason) {
        assertThatThrownBy(() -> semanticValidator.validate(candidate, source))
                .isInstanceOfSatisfying(
                        MemoryCandidateValidationException.class,
                        error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
