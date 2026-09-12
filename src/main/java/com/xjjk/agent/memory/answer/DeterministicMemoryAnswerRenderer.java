package com.xjjk.agent.memory.answer;

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
    private static final Pattern PROGRAMMING_LANGUAGE =
            Pattern.compile("(?i)(Java|Python)");

    private final MemoryCategoryContentPolicy contentPolicy;
    private final int maxContentLength;

    public DeterministicMemoryAnswerRenderer(
            MemoryCategoryContentPolicy contentPolicy,
            UserMemoryProperties properties) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "记忆内容策略不能为空");
        this.maxContentLength = Objects.requireNonNull(
                properties, "用户记忆配置不能为空").maxContentLength();
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
        Optional<String> canonical = contentPolicy.canonicalize(
                type.memoryCategory(), memory.content());
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        if (type == DirectMemoryQuestionType.PROGRAMMING_LANGUAGE) {
            Matcher matcher = PROGRAMMING_LANGUAGE.matcher(canonical.get());
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
