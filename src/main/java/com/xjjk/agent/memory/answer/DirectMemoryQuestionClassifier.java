package com.xjjk.agent.memory.answer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 无模型、偏保守的本人长期记忆问题分类器。 */
@Component
public class DirectMemoryQuestionClassifier {
    private static final List<String> BUSINESS = List.of(
            "订单", "物流", "退款", "退货", "换货", "售后", "商品",
            "客户", "库存", "发票", "支付", "签收", "快递");
    private static final List<String> MULTI_INTENT = List.of(
            "并且", "同时", "另外", "以及", "；", ";");
    private static final List<String> QUESTION = List.of(
            "什么", "怎么", "哪种", "哪里", "哪儿", "在哪",
            "是否", "吗", "多大", "几岁", "？", "?");

    public Optional<DirectMemoryQuestionType> classify(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()
                || rawQuery.codePointCount(0, rawQuery.length()) > 80) {
            return Optional.empty();
        }
        String query = rawQuery.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        if (!containsAny(query, QUESTION) || !query.contains("我")
                || containsAny(query, BUSINESS) || containsAny(query, MULTI_INTENT)) {
            return Optional.empty();
        }

        List<DirectMemoryQuestionType> matches = new ArrayList<>();
        if (containsAny(query, List.of("多大年纪", "多大年龄", "几岁"))
                || (query.contains("年龄") && containsAny(query, List.of("什么", "多少")))) {
            matches.add(DirectMemoryQuestionType.AGE);
        }
        if (containsAny(query, List.of("称呼", "叫我", "怎么叫"))) {
            matches.add(DirectMemoryQuestionType.PREFERRED_NAME);
        }
        if (query.contains("语言偏好")
                || (query.contains("语言") && containsAny(
                query, List.of("回答", "回复", "交流", "沟通")))) {
            matches.add(DirectMemoryQuestionType.ANSWER_LANGUAGE);
        }
        if (query.contains("编程语言")
                || (query.contains("语言") && containsAny(
                query, List.of("编程", "开发", "代码", "程序")))) {
            matches.add(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE);
        }
        if (containsAny(query, List.of(
                "在哪里工作", "在哪工作", "工作单位", "哪家公司工作", "任职公司"))) {
            matches.add(DirectMemoryQuestionType.CURRENT_EMPLOYER);
        }
        if (containsAny(query, List.of("回答风格", "回复风格"))
                || (containsAny(query, List.of("回答", "回复"))
                && containsAny(query, List.of("简洁", "详细", "怎么")))) {
            matches.add(DirectMemoryQuestionType.ANSWER_STYLE);
        }
        boolean occupationQuestion = containsAny(query, List.of(
                "做什么工作", "从事什么", "什么职业", "职业是什么", "主要做什么"));
        boolean historical = containsAny(query, List.of(
                "以前", "过去", "曾经", "原来", "之前"));
        if (occupationQuestion) {
            matches.add(historical
                    ? DirectMemoryQuestionType.HISTORICAL_OCCUPATION
                    : DirectMemoryQuestionType.CURRENT_OCCUPATION);
        }
        if (containsAny(query, List.of("工作范围", "技术栈"))) {
            matches.add(DirectMemoryQuestionType.WORK_SCOPE);
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private boolean containsAny(String value, List<String> needles) {
        return needles.stream().anyMatch(value::contains);
    }
}
