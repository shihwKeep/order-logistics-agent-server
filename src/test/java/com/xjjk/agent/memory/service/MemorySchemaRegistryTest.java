package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySchemaRegistryTest {

    private final MemorySchemaRegistry registry =
            new MemorySchemaRegistry(new ObjectMapper());

    @Test
    void resolvesStableSlotsWithoutInspectingTheSurroundingSentence() {
        var first = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Java", "Java"));
        var second = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Java", "Java"));

        assertThat(first.canonicalKey())
                .isEqualTo("work.primary_programming_language");
        assertThat(first.canonicalContent())
                .isEqualTo("用户主要使用 Java 进行开发");
        assertThat(first.valueJson()).isEqualTo("\"Java\"");
        assertThat(first.requiresSemanticVerification()).isFalse();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void supportsValueLevelAliasesAndOpenProgrammingLanguages() {
        assertThat(registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "JavaScript", "js")).canonicalContent())
                .isEqualTo("用户主要使用 JavaScript 进行开发");
        assertThat(registry.resolve(candidate(
                MemoryType.COMMUNICATION_PREFERENCE, "answer_language",
                "英文", "英语")).canonicalContent())
                .isEqualTo("用户偏好使用英文交流");

        var openLanguage = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Elixir", "Elixir"));
        assertThat(openLanguage.canonicalContent())
                .isEqualTo("用户主要使用 Elixir 进行开发");
        assertThat(openLanguage.requiresSemanticVerification()).isTrue();
    }

    @Test
    void supportsOpenCommunicationLanguagesAndAnswerStylesWithoutEnumeratingThem() {
        var language = registry.resolve(candidate(
                MemoryType.COMMUNICATION_PREFERENCE, "answer_language",
                "葡萄牙语", "葡萄牙语"));
        var style = registry.resolve(candidate(
                MemoryType.RESPONSE_PREFERENCE, "answer_style",
                "苏格拉底式引导", "苏格拉底式引导"));

        assertThat(language.canonicalContent()).isEqualTo("用户偏好使用葡萄牙语交流");
        assertThat(language.requiresSemanticVerification()).isTrue();
        assertThat(style.canonicalContent()).isEqualTo("用户偏好苏格拉底式引导回答");
        assertThat(style.requiresSemanticVerification()).isTrue();
    }

    @Test
    void createsServerOwnedKeysForOpenFacts() {
        var result = registry.resolve(candidate(
                MemoryType.STABLE_PREFERENCE, "arbitrary_model_key",
                "我喜欢先看示例再看解释", "我喜欢先看示例再看解释"));

        assertThat(result.canonicalKey())
                .matches("fact\\.stable_preference\\.[0-9a-f]{64}");
        assertThat(result.canonicalKey()).doesNotContain("arbitrary_model_key");
        assertThat(result.requiresSemanticVerification()).isTrue();
    }

    @Test
    void rejectsUnknownPredicatesAndUnsupportedValueRewrites() {
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.PROFILE, "unknown_profile_field", "简洁", "简洁")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.SCHEMA);
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Python", "Java")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.UNSUPPORTED);
    }

    private static MemoryFactCandidate candidate(
            MemoryType type,
            String predicate,
            String value,
            String valueEvidence) {
        return new MemoryFactCandidate(
                type, predicate, value, valueEvidence,
                "上下文中的" + valueEvidence + "事实", MemoryStability.STABLE, 0.96);
    }
}
