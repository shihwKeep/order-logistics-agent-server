package com.xjjk.agent.chat.service.stream;

import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 校验复合回答是否把规则建议或轨迹备注扩写成未经业务事实确认的结论。 */
@Component
public final class CompositeAnswerPolicyValidator {

    private static final List<String> EXECUTION_MARKERS = List.of(
            "已生成预警",
            "已联系承运商",
            "已启动核查",
            "正在核实中",
            "已升级");

    private static final List<String> TRACE_VALIDITY_MARKERS = List.of(
            "无有效更新",
            "无效更新",
            "非标准文本",
            "有效性",
            "真实性",
            "有效官方",
            "官方更新",
            "若无效",
            "无法验证",
            "乱码",
            "非官方",
            "轨迹内容异常");

    private static final List<String> TRACE_VALIDITY_QUESTION_MARKERS = List.of(
            "轨迹是否有效",
            "轨迹有效性",
            "轨迹真实性",
            "是否官方更新",
            "是否为官方");

    private static final List<String> SPECIAL_CONDITION_MARKERS = List.of(
            "高价值",
            "冷链");

    public List<String> validate(String answer, String verifiedContext) {
        return validate("", answer, verifiedContext);
    }

    public List<String> validate(
            String userMessage,
            String answer,
            String verifiedContext) {
        if (!StringUtils.hasText(answer)) {
            return List.of("EMPTY_ANSWER");
        }
        String question = userMessage == null ? "" : userMessage;
        String context = businessFactContext(verifiedContext);
        List<String> violations = new ArrayList<>();
        if (containsUnverifiedMarker(answer, context, EXECUTION_MARKERS)) {
            violations.add("UNVERIFIED_EXECUTION");
        }
        if (!containsAny(question, TRACE_VALIDITY_QUESTION_MARKERS)
                && containsUnverifiedMarker(answer, context, TRACE_VALIDITY_MARKERS)) {
            violations.add("TRACE_VALIDITY_INFERENCE");
        }
        if (containsUnaskedMarker(
                question, answer, context, SPECIAL_CONDITION_MARKERS)) {
            violations.add("UNSUPPORTED_SPECIAL_CONDITION");
        }
        return List.copyOf(violations);
    }

    private String businessFactContext(String verifiedContext) {
        if (verifiedContext == null) {
            return "";
        }
        int knowledgeBoundary = verifiedContext.indexOf("企业知识依据");
        return knowledgeBoundary < 0
                ? verifiedContext
                : verifiedContext.substring(0, knowledgeBoundary);
    }

    private boolean containsUnverifiedMarker(
            String answer,
            String verifiedContext,
            List<String> markers) {
        return markers.stream().anyMatch(marker ->
                answer.contains(marker) && !verifiedContext.contains(marker));
    }

    private boolean containsUnaskedMarker(
            String question,
            String answer,
            String businessFacts,
            List<String> markers) {
        return markers.stream().anyMatch(marker ->
                answer.contains(marker)
                        && !question.contains(marker)
                        && !businessFacts.contains(marker));
    }

    private boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }
}
