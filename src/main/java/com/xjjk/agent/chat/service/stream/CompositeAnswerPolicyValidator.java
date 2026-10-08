package com.xjjk.agent.chat.service.stream;

import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final List<String> PRICING_POLICY_CLAIM_MARKERS = List.of(
            "符合企业定价策略",
            "符合定价策略",
            "成交价合理",
            "价格合理",
            "当前价格合理");

    private static final List<String> PRICING_BASELINE_MARKERS = List.of(
            "定价基准",
            "历史价",
            "活动价",
            "成本参考",
            "价格快照");

    private static final List<String> PRICING_UNKNOWN_MARKERS = List.of(
            "无法确认",
            "无法判断",
            "不能确认",
            "不能判断");

    private static final List<String> PRODUCT_PRICE_SNAPSHOT_CLAIM_MARKERS = List.of(
            "符合订单保存下单时价格快照",
            "符合\"订单保存下单时价格快照",
            "符合“订单保存下单时价格快照",
            "符合‘订单保存下单时价格快照");

    private static final List<String> PRODUCT_LISTING_PRICE_LINK_MARKERS = List.of(
            "标价符合上架状态要求",
            "价格符合上架状态要求",
            "售价符合上架状态要求",
            "标价符合上架要求",
            "价格符合上架要求",
            "标价符合上架商品基础展示要求",
            "价格符合上架商品基础展示要求",
            "售价符合上架商品基础展示要求",
            "标价符合商品基础展示要求",
            "标价本身符合上架商品基础展示要求");

    private static final List<String> PRODUCT_LISTING_PRICE_RULE_MARKERS = List.of(
            "标价本身无逻辑冲突",
            "当前标价无逻辑冲突",
            "标价非负、非零",
            "当前标价非负、非零",
            "标价满足单行实付不得为负",
            "标价本身无负值",
            "当前标价无负值",
            "标价无负值",
            "标价非负",
            "价格非负",
            "当前标价本身为正数且非零",
            "标价为正数且非零",
            "价格为正数且非零",
            "标价本身为正数且非零",
            "标价本身为正数",
            "符合基础展示要求",
            "符合基础显示要求",
            "符合商品基础展示要求",
            "符合商品基础显示要求");

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:¥|￥|(?:当前)?(?:标价|售价|价格|单价|成交价))\\s*(?:为|是|[=:：])?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)");

    private static final List<String> UNQUERIED_AFTER_SALE_MARKERS = List.of(
            "未查询到关联售后工单",
            "未查询到售后工单",
            "尚未关联售后工单",
            "没有售后工单",
            "不存在售后工单");

    private static final List<String> UNSUPPORTED_AFTER_SALE_CATEGORY_MARKERS = List.of(
            "属普通食品",
            "属于普通食品",
            "非定制",
            "非鲜活",
            "非数字类",
            "定制/鲜活/数字");

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
        if (containsAny(answer, PRICING_POLICY_CLAIM_MARKERS)
                && !containsAny(context, PRICING_BASELINE_MARKERS)
                && !containsAny(answer, PRICING_UNKNOWN_MARKERS)) {
            violations.add("UNSUPPORTED_PRICING_POLICY_CLAIM");
        }
        if (hasResultKind(context, "product-list")
                && !hasResultKind(context, "order-list")
                && containsAny(answer, PRODUCT_PRICE_SNAPSHOT_CLAIM_MARKERS)) {
            violations.add("UNSUPPORTED_PRODUCT_PRICE_SNAPSHOT");
        }
        if (hasResultKind(context, "product-list")
                && !hasResultKind(context, "order-list")
                && containsAny(answer, PRODUCT_LISTING_PRICE_LINK_MARKERS)) {
            violations.add("UNSUPPORTED_PRODUCT_LISTING_PRICE_LINK");
        }
        if (hasResultKind(context, "product-list")
                && !hasResultKind(context, "order-list")
                && containsAny(answer, PRODUCT_LISTING_PRICE_RULE_MARKERS)) {
            violations.add("UNSUPPORTED_PRODUCT_LISTING_PRICE_RULE");
        }
        if (hasProductPriceMismatch(answer, context)) {
            violations.add("PRODUCT_PRICE_MISMATCH");
        }
        if (omitsCompletedBusinessBranch(answer, context)) {
            violations.add("INCOMPLETE_COMPOSITE_ANSWER");
        }
        if (!hasAfterSaleResult(context)
                && containsAny(answer, UNQUERIED_AFTER_SALE_MARKERS)) {
            violations.add("UNQUERIED_AFTER_SALE_RESULT");
        }
        if (!containsAny(context, List.of("签收", "签收时间", "已签收", "未签收"))
                && containsAny(answer, List.of("尚未签收", "未签收", "已经签收"))) {
            violations.add("UNVERIFIED_AFTER_SALE_FACT");
        }
        if (!containsAny(context, List.of("品类", "类目", "商品类别"))
                && containsAny(answer, UNSUPPORTED_AFTER_SALE_CATEGORY_MARKERS)) {
            violations.add("UNSUPPORTED_AFTER_SALE_CATEGORY");
        }
        if (!containsAny(context, List.of("例外审核", "物流停滞证据"))
                && containsAny(answer, List.of("例外审核", "凭物流停滞证据"))) {
            violations.add("UNSUPPORTED_AFTER_SALE_EXCEPTION");
        }
        if (containsAny(question, List.of("售后", "退货", "换货", "退款"))
                && !containsAny(answer, List.of(
                "售后", "退货", "换货", "退款", "签收", "商品完好", "无法确认"))) {
            violations.add("INCOMPLETE_AFTER_SALE_ANALYSIS");
        }
        return List.copyOf(violations);
    }

    private boolean hasAfterSaleResult(String businessFacts) {
        return hasResultKind(businessFacts, "after-sale-list")
                || hasResultKind(businessFacts, "after-sale-detail");
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

    private boolean hasProductPriceMismatch(String answer, String businessFacts) {
        if (!hasResultKind(businessFacts, "product-list")) {
            return false;
        }
        Set<BigDecimal> verifiedPrices = extractPrices(businessFacts);
        if (verifiedPrices.isEmpty()) {
            return false;
        }
        for (BigDecimal answerPrice : extractPrices(answer)) {
            if (!verifiedPrices.contains(answerPrice)) {
                return true;
            }
        }
        return false;
    }

    private Set<BigDecimal> extractPrices(String text) {
        Set<BigDecimal> prices = new HashSet<>();
        Matcher matcher = PRICE_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            try {
                prices.add(new BigDecimal(matcher.group(1)).stripTrailingZeros());
            } catch (NumberFormatException ignored) {
                // Ignore malformed model text; it will be handled by the normal answer boundary.
            }
        }
        return prices;
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
