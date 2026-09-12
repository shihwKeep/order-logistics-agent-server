package com.xjjk.agent.memory.recall;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 无模型、低成本且偏保守的语义记忆召回门控。 */
@Component
public class MemoryRecallGate {
    private static final List<String> MEMORY_SIGNALS = List.of(
            "记得", "还记得", "以前", "曾经", "告诉过", "偏好", "习惯", "喜欢",
            "回答风格", "语言偏好", "编程语言", "技术栈", "做什么工作", "工作范围",
            "主要使用", "平时使用", "称呼", "叫我", "怎么叫", "关于我");
    private static final List<String> FIRST_PERSON = List.of(
            "我", "我的", "本人", "咱", "我们");
    private static final List<String> BUSINESS_ONLY = List.of(
            "订单", "物流", "退款", "退货", "换货", "售后", "商品", "发票", "投诉",
            "签收", "客户", "库存", "快递");

    public boolean shouldRetrieve(String query) {
        if (query == null || query.isBlank() || query.length() > 2_000) {
            return false;
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        boolean memorySignal = containsAny(normalized, MEMORY_SIGNALS);
        if (!memorySignal) {
            return false;
        }
        boolean personal = containsAny(normalized, FIRST_PERSON);
        boolean categorySpecific = containsAny(normalized, List.of(
                "回答风格", "语言偏好", "编程语言", "技术栈", "工作范围", "称呼"));
        if (!personal && !categorySpecific) {
            return false;
        }
        return !containsAny(normalized, BUSINESS_ONLY) || categorySpecific;
    }

    private boolean containsAny(String value, List<String> needles) {
        return needles.stream().anyMatch(value::contains);
    }
}
