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
            "乱码",
            "非官方",
            "轨迹内容异常");

    public List<String> validate(String answer, String verifiedContext) {
        if (!StringUtils.hasText(answer)) {
            return List.of("EMPTY_ANSWER");
        }
        String context = businessFactContext(verifiedContext);
        List<String> violations = new ArrayList<>();
        if (containsUnverifiedMarker(answer, context, EXECUTION_MARKERS)) {
            violations.add("UNVERIFIED_EXECUTION");
        }
        if (containsUnverifiedMarker(answer, context, TRACE_VALIDITY_MARKERS)) {
            violations.add("TRACE_VALIDITY_INFERENCE");
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
}
