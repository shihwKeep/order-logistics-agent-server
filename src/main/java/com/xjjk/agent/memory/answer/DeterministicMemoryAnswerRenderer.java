package com.xjjk.agent.memory.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.recall.RecalledMemory;
import com.xjjk.agent.memory.service.MemoryCategoryContentPolicy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只渲染封闭语法能够重新规范化的记忆正文。 */
@Component
public class DeterministicMemoryAnswerRenderer {
    private static final Pattern LEGACY_PROGRAMMING_LANGUAGE =
            Pattern.compile("(?i)(Java|Python)");
    private final MemoryCategoryContentPolicy contentPolicy;
    private final int maxContentLength;
    private final ObjectMapper objectMapper;

    public DeterministicMemoryAnswerRenderer(
            MemoryCategoryContentPolicy contentPolicy,
            UserMemoryProperties properties) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "记忆内容策略不能为空");
        this.maxContentLength = Objects.requireNonNull(
                properties, "用户记忆配置不能为空").maxContentLength();
        this.objectMapper = new ObjectMapper();
    }

    public Optional<String> render(
            DirectMemoryQuestionType type,
            RecalledMemory memory) {
        Objects.requireNonNull(type, "问题类型不能为空");
        if (memory == null
                || !type.memoryCategory().name().equals(memory.category())
                || memory.content() == null
                || memory.content().codePointCount(0, memory.content().length())
                > maxContentLength
                || memory.content().codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        if (Integer.valueOf(2).equals(memory.schemaVersion())) {
            return renderStructured(type, memory);
        }
        Optional<String> canonical = contentPolicy.canonicalize(
                type.memoryCategory(), memory.content());
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        if (type == DirectMemoryQuestionType.PROGRAMMING_LANGUAGE) {
            Matcher matcher = LEGACY_PROGRAMMING_LANGUAGE.matcher(canonical.get());
            if (!matcher.find()) {
                return Optional.empty();
            }
            String language = matcher.group(1).equalsIgnoreCase("java")
                    ? "Java" : "Python";
            return Optional.of("根据您之前提供的信息，您平时主要使用 "
                    + language + "。");
        }
        String personalized = canonical.get().startsWith("用户")
                ? "您" + canonical.get().substring(2) : canonical.get();
        return Optional.of("根据您之前提供的信息，" + personalized + "。");
    }

    private Optional<String> renderStructured(
            DirectMemoryQuestionType type,
            RecalledMemory memory) {
        if (!"STABLE".equals(memory.stability())
                || memory.predicateName() == null
                || !type.predicateNames().contains(memory.predicateName())
                || memory.valueJson() == null) {
            return Optional.empty();
        }
        final String value;
        try {
            var node = objectMapper.readTree(memory.valueJson());
            if (!node.isTextual()) {
                return Optional.empty();
            }
            value = node.textValue().strip();
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
        if (value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        String fact = switch (type) {
            case PREFERRED_NAME -> "您希望被称为" + value;
            case PROGRAMMING_LANGUAGE -> "您平时主要使用 " + value;
            case ANSWER_LANGUAGE -> "您偏好使用" + value + "交流";
            case ANSWER_STYLE -> "您偏好" + value + "回答";
            case WORK_SCOPE -> switch (memory.predicateName()) {
                case "occupation" -> "您的职业是" + value;
                case "technology_stack" -> "您常用技术栈是" + value;
                case "common_scope" -> "您常用工作范围是" + value;
                default -> null;
            };
        };
        return fact == null ? Optional.empty()
                : Optional.of("根据您之前提供的信息，" + fact + "。");
    }

    public String notRemembered(DirectMemoryQuestionType type) {
        return switch (Objects.requireNonNull(type, "问题类型不能为空")) {
            case PREFERRED_NAME -> "我还没有记住您偏好的称呼。";
            case PROGRAMMING_LANGUAGE -> "我还没有记住您常用的编程语言。";
            case WORK_SCOPE -> "我还没有记住您的工作范围。";
            case ANSWER_LANGUAGE -> "我还没有记住您的回答语言偏好。";
            case ANSWER_STYLE -> "我还没有记住您的回答风格偏好。";
        };
    }
}
