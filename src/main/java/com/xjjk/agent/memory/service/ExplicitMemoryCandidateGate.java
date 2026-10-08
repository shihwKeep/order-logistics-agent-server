package com.xjjk.agent.memory.service;

import java.util.List;

/**
 * 显式记忆语义分类的低成本前置门控。命中只代表值得分类，不能授权写入。
 */
public class ExplicitMemoryCandidateGate {

    private static final List<String> DIRECT_MEMORY_MARKERS = List.of("记住", "记下", "别忘");
    private static final List<String> FUTURE_MARKERS = List.of("以后", "今后", "之后", "从今往后", "后面");
    private static final List<String> PERSONALIZATION_MARKERS = List.of(
            "叫我", "称呼", "回复", "回答", "交流", "使用", "默认");
    private static final List<String> PERSONAL_SUBJECT_MARKERS = List.of(
            "我", "我的", "本人", "偏好", "习惯");

    private final int maxMessageCodePoints;

    public ExplicitMemoryCandidateGate(int maxMessageCodePoints) {
        if (maxMessageCodePoints < 1) {
            throw new IllegalArgumentException("maxMessageCodePoints must be positive");
        }
        this.maxMessageCodePoints = maxMessageCodePoints;
    }

    public boolean mightContainExplicitMemory(String input) {
        // 第一步：门控只处理长度受控的非空消息，避免低价值或异常大文本触发模型调用。
        if (input == null) {
            return false;
        }
        String message = ExplicitMemoryCommandDetector.normalizeWhitespace(input);
        if (message.isBlank()
                || message.codePointCount(0, message.length()) > maxMessageCodePoints) {
            return false;
        }
        // 第二步：明确记忆动词直接命中；这里只表示“值得分类”，并不代表允许保存。
        if (containsAny(message, DIRECT_MEMORY_MARKERS)) {
            return true;
        }
        // “保存”必须同时出现个人主体，减少“保存订单/保存文件”等业务表达误判。
        if (message.contains("保存") && containsAny(message, PERSONAL_SUBJECT_MARKERS)) {
            return true;
        }
        // 没有直接记忆动词时，必须同时具有未来语义和个性化行为，才进入语义兜底。
        return containsAny(message, FUTURE_MARKERS)
                && containsAny(message, PERSONALIZATION_MARKERS);
    }

    private static boolean containsAny(String message, List<String> markers) {
        return markers.stream().anyMatch(message::contains);
    }
}
