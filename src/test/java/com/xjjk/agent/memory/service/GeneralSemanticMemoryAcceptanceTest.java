package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通用语义记忆契约的离线验收集，避免实现退化为整句关键词模板。 */
class GeneralSemanticMemoryAcceptanceTest {

    private final ImplicitMemoryCandidateValidator validator =
            new ImplicitMemoryCandidateValidator(
                    new MemorySensitiveContentPolicy(new SensitiveContentSanitizer()),
                    new MemorySchemaRegistry(new ObjectMapper()),
                    properties(), 512, 512);

    @ParameterizedTest(name = "{0}")
    @MethodSource("stableFacts")
    void acceptsGroundedStableFactsAcrossDomains(
            String source,
            MemoryFactCandidate candidate,
            String expectedContent,
            String expectedVerification) {
        var result = validator.validate(candidate, source);

        assertThat(result.canonicalContent()).isEqualTo(expectedContent);
        assertThat(result.verificationMethod()).isEqualTo(expectedVerification);
    }

    private static Stream<Arguments> stableFacts() {
        return Stream.of(
                fact("Java 是我主要使用的开发语言",
                        MemoryType.WORK_CONTEXT, "primary_programming_language",
                        "Java", "Java", "用户主要使用 Java 进行开发", "DETERMINISTIC"),
                fact("工作中我长期以 Elixir 编程",
                        MemoryType.WORK_CONTEXT, "primary_programming_language",
                        "Elixir", "Elixir", "用户主要使用 Elixir 进行开发", "SEMANTIC_REQUIRED"),
                fact("以后和我交流都使用葡萄牙语",
                        MemoryType.COMMUNICATION_PREFERENCE, "answer_language",
                        "葡萄牙语", "葡萄牙语", "用户偏好使用葡萄牙语交流", "SEMANTIC_REQUIRED"),
                fact("回答时请一直采用苏格拉底式引导",
                        MemoryType.RESPONSE_PREFERENCE, "answer_style",
                        "苏格拉底式引导", "苏格拉底式引导",
                        "用户偏好苏格拉底式引导回答", "SEMANTIC_REQUIRED"),
                fact("我长期负责跨境电商供应链平台建设",
                        MemoryType.WORK_CONTEXT, "occupation",
                        "跨境电商供应链平台建设", "跨境电商供应链平台建设",
                        "用户当前的职业是跨境电商供应链平台建设", "SEMANTIC_REQUIRED"),
                fact("我今年32岁",
                        MemoryType.PROFILE, "age",
                        "32", "32岁", "用户曾表示年龄为32岁", "SEMANTIC_REQUIRED"),
                fact("我最喜欢的城市是杭州",
                        MemoryType.PROFILE, "favorite_city",
                        "杭州", "杭州", "用户提供的个人画像事实（favorite_city）是杭州",
                        "SEMANTIC_REQUIRED"),
                fact("我一直喜欢先看例子再看原理",
                        MemoryType.STABLE_PREFERENCE, "learning_order",
                        "先看例子再看原理", "先看例子再看原理",
                        "用户的稳定偏好是先看例子再看原理", "SEMANTIC_REQUIRED")
        );
    }

    @org.junit.jupiter.api.Test
    void rejectsSensitiveOpenProfileFactsThroughTheFullValidator() {
        String source = "我的手机号是13800138000";
        MemoryFactCandidate candidate = new MemoryFactCandidate(
                MemoryType.PROFILE, "phone_number", "13800138000", "13800138000",
                source, MemoryStability.STABLE, 0.99);

        assertThatThrownBy(() -> validator.validate(candidate, source))
                .isInstanceOfSatisfying(
                        MemoryCandidateValidationException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(MemoryCandidateValidationException.Reason.SENSITIVE));
    }

    private static Arguments fact(
            String source,
            MemoryType type,
            String predicate,
            String value,
            String evidence,
            String expectedContent,
            String verification) {
        return Arguments.of(source,
                new MemoryFactCandidate(type, predicate, value, evidence, source,
                        MemoryStability.STABLE, 0.96),
                expectedContent, verification);
    }

    private static ImplicitMemoryProperties properties() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-semantic-v2", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
