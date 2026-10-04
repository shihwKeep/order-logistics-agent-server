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

    private static final List<String> MATERIAL_MARKERS = List.of(
            "完整支付凭证",
            "支付凭证",
            "签收凭证",
            "补充凭证",
            "提供凭证",
            "提供证明",
            "补充材料");

    private static final List<String> UNVERIFIED_BUSINESS_FACT_MARKERS = List.of(
            "系统核算金额已固化",
            "金额已固化",
            "价格已固化",
            "价格已冻结",
            "价格已锁定");

    private static final List<String> UNSUPPORTED_PRICING_MARKERS = List.of(
            "满减",
            "运费券",
            "积分抵扣");

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
        if (containsUnaskedMarker(
                question, answer, verifiedContext == null ? "" : verifiedContext,
                MATERIAL_MARKERS)) {
            violations.add("UNSUPPORTED_REQUESTED_MATERIAL");
        }
        if (containsUnverifiedMarker(
                answer, context, UNVERIFIED_BUSINESS_FACT_MARKERS)) {
            violations.add("UNVERIFIED_BUSINESS_FACT");
        }
        if (containsUnaskedMarker(
                question, answer, context, UNSUPPORTED_PRICING_MARKERS)) {
            violations.add("UNSUPPORTED_PRICING_DETAIL");
        }
        if (omitsCompletedBusinessBranch(answer, context)) {
            violations.add("INCOMPLETE_COMPOSITE_ANSWER");
        }
        return List.copyOf(violations);
    }

    private boolean omitsCompletedBusinessBranch(String answer, String businessFacts) {
        if (hasResultKind(businessFacts, "order-list")
                && !containsAny(answer, List.of("订单", "商品", "金额", "单价", "小计", "SKU"))) {
            return true;
        }
        if (hasResultKind(businessFacts, "logistics-timeline")
                && !containsAny(answer, List.of("物流", "轨迹", "运单", "在途", "派送", "干线"))) {
            return true;
        }
        if (hasResultKind(businessFacts, "product-list")
                && !containsAny(answer, List.of("商品", "SKU", "价格", "库存"))) {
            return true;
        }
        if (hasResultKind(businessFacts, "after-sale-list")
                || hasResultKind(businessFacts, "after-sale-detail")) {
            return !containsAny(answer, List.of("售后", "退货", "换货", "退款"));
        }
        if (hasResultKind(businessFacts, "customer-list")) {
            return !answer.contains("客户");
        }
        return false;
    }

    private boolean hasResultKind(String businessFacts, String resultKind) {
        return businessFacts.contains(resultKind + "：")
                || businessFacts.contains(resultKind + ":");
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
