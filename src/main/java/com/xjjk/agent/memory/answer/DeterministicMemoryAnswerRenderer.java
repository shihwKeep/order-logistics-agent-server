package com.xjjk.agent.memory.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
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
        // 第一步：再次核对类别、正文长度和控制字符；召回成功不等于可以直接展示。
        if (memory == null
                || !type.memoryCategory().name().equals(memory.category())
                || memory.content() == null
                || memory.content().codePointCount(0, memory.content().length())
                > maxContentLength
                || memory.content().codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        // 第二步：新版结构化记忆优先从 valueJson 和 predicate 渲染；旧数据走封闭语法兼容路径。
        if (Integer.valueOf(2).equals(memory.schemaVersion())
                || Integer.valueOf(3).equals(memory.schemaVersion())) {
            return renderStructured(type, memory);
        }
        Optional<String> canonical = contentPolicy.canonicalize(
                type.memoryCategory(), memory.content());
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        if (type == DirectMemoryQuestionType.PROGRAMMING_LANGUAGE) {
            // 旧版编程语言正文只允许重新识别出受控语言后输出，避免自由文本直接进入回答。
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
        // 结构化事实必须稳定、predicate 与问题匹配、值存在并且当前/历史范围一致。
        if (!("STABLE".equals(memory.stability())
                || "TIME_BOUND".equals(memory.stability()))
                || memory.predicateName() == null
                || !type.predicateNames().contains(memory.predicateName())
                || memory.valueJson() == null
                || !matchesTemporalScope(type, memory)) {
            return Optional.empty();
        }
        final String value;
        try {
            // valueJson 必须是单一文本值；对象、数组或损坏 JSON 均拒绝确定性直答。
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
            // 每种封闭问题使用固定句式，避免再调用模型造成“有记忆却回答不知道”。
            case PREFERRED_NAME -> "您希望被称为" + value;
            case AGE -> renderAge(value);
            case PROGRAMMING_LANGUAGE -> "您平时主要使用 " + value;
            case CURRENT_EMPLOYER -> "您当前工作单位是" + value;
            case CURRENT_OCCUPATION -> "您目前的职业是" + value;
            case HISTORICAL_OCCUPATION -> "您以前从事过" + value;
            case ANSWER_LANGUAGE -> "您偏好使用" + value + "交流";
            case ANSWER_STYLE -> "您偏好" + value + "回答";
            case WORK_SCOPE -> switch (memory.predicateName()) {
                case "technology_stack" -> "您常用技术栈是" + value;
                case "common_scope" -> "您常用工作范围是" + value;
                default -> null;
            };
        };
        return fact == null ? Optional.empty()
                : Optional.of("根据您之前提供的信息，" + fact + "。");
    }

    private boolean matchesTemporalScope(
            DirectMemoryQuestionType type,
            RecalledMemory memory) {
        // Schema v2 没有完整历史版本语义，只兼容为当前事实；v3 必须严格匹配时间范围。
        if (Integer.valueOf(2).equals(memory.schemaVersion())) {
            return type.temporalScope() == MemoryTemporalScope.CURRENT;
        }
        return type.temporalScope().name().equals(memory.temporalScope())
                && memory.observedAt() != null
                && (type.temporalScope() == MemoryTemporalScope.HISTORICAL
                    || memory.validFrom() != null);
    }

    private String renderAge(String value) {
        // 年龄只接受合理的十进制整数，并使用“当时”避免把旧观察值自动推算成当前年龄。
        if (!value.matches("[0-9]{1,3}")) {
            return null;
        }
        int age = Integer.parseInt(value);
        return age <= 120 ? "您当时" + age + "岁" : null;
    }

    public String notRemembered(DirectMemoryQuestionType type) {
        // 未命中时使用与问题类型对应的固定提示，不泄露内部 predicate 或存储结构。
        return switch (Objects.requireNonNull(type, "问题类型不能为空")) {
            case PREFERRED_NAME -> "我还没有记住您偏好的称呼。";
            case AGE -> "我还没有记住您此前提供的年龄。";
            case PROGRAMMING_LANGUAGE -> "我还没有记住您常用的编程语言。";
            case CURRENT_EMPLOYER -> "我还没有记住您的工作单位。";
            case CURRENT_OCCUPATION -> "我还没有记住您当前的职业。";
            case HISTORICAL_OCCUPATION -> "我还没有记住您过去的职业。";
            case WORK_SCOPE -> "我还没有记住您的工作范围。";
            case ANSWER_LANGUAGE -> "我还没有记住您的回答语言偏好。";
            case ANSWER_STYLE -> "我还没有记住您的回答风格偏好。";
        };
    }
}
