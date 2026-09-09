package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.config.BusinessQueryEnforcementProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仅根据当前用户消息制定业务查询计划。
 *
 * <p>高置信规则只接管已有确定性动作；无法可靠提取参数时只标记需要本轮新鲜结果，
 * 具体工具和参数仍由模型决定。历史消息和摘要不会参与这里的判断。</p>
 */
@Component
@RequiredArgsConstructor
public class BusinessQueryPlanner {

    private static final Pattern ORDER_CODE = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])(XJ[A-Z0-9_-]{8,61})(?![A-Z0-9_-])");
    private static final Pattern CUSTOMER_CODE = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])(C\\d{10,31})(?![A-Z0-9_-])");
    private static final Pattern AFTER_SALE_CODE = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])([A-Z]{2,8}\\d{8}(?:[_-]?\\d{3,32}))(?![A-Z0-9_-])");

    private final BusinessQueryEnforcementProperties properties;

    public BusinessQueryPlan plan(String rawMessage) {
        if (!properties.enabled() || rawMessage == null || rawMessage.isBlank()) {
            return BusinessQueryPlan.general();
        }
        String message = rawMessage.strip();
        if (isKnowledgeQuestion(message)) {
            return BusinessQueryPlan.general();
        }

        boolean product = containsAny(message, "商品", "SKU", "sku", "库存", "上下架");
        boolean customer = message.contains("客户");
        boolean order = message.contains("订单");
        boolean logistics = containsAny(message, "物流", "运单", "轨迹");
        boolean afterSale = containsAny(message, "售后", "退货", "换货", "退款");
        boolean explicitQuery = containsAny(
                message, "查询", "查看", "查下", "查一下", "帮我查", "帮忙查");
        boolean currentFactQuestion = containsAny(
                message, "当前", "现在", "还有", "多少", "有没有", "信息",
                "详情", "状态", "最近", "本月", "今天", "进度", "吗");
        if ((!explicitQuery && !currentFactQuestion)
                || !(product || customer || order || logistics || afterSale)) {
            return BusinessQueryPlan.general();
        }

        int domains = count(product, customer, logistics, afterSale);
        if (domains == 1 && logistics) {
            String code = find(ORDER_CODE, message);
            if (code != null) {
                return BusinessQueryPlan.direct(
                        new ChatActionRequest("QUERY_ORDER_LOGISTICS", code),
                        "logistics-timeline");
            }
        }
        if (domains == 1 && customer && order) {
            String code = find(CUSTOMER_CODE, message);
            if (code != null) {
                return BusinessQueryPlan.direct(
                        new ChatActionRequest("QUERY_CUSTOMER_ORDERS", null, code),
                        "order-list");
            }
        }
        if (domains == 1 && afterSale
                && containsAny(message, "售后单", "售后工单")
                && !message.contains("客户") && !message.contains("订单")) {
            String code = find(AFTER_SALE_CODE, message);
            if (code != null) {
                return BusinessQueryPlan.direct(
                        new ChatActionRequest(
                                "QUERY_AFTER_SALE_DETAIL", null, null, code),
                        "after-sale-detail");
            }
        }

        Set<String> acceptedKinds = acceptedKinds(
                product, customer, order, logistics, afterSale, message);
        return acceptedKinds.isEmpty()
                ? BusinessQueryPlan.general()
                : BusinessQueryPlan.modelRequired(acceptedKinds);
    }

    private Set<String> acceptedKinds(
            boolean product,
            boolean customer,
            boolean order,
            boolean logistics,
            boolean afterSale,
            String message) {
        Set<String> kinds = new LinkedHashSet<>();
        if (logistics) {
            kinds.add("logistics-timeline");
        } else if (customer && order) {
            kinds.add("order-list");
        } else {
            if (afterSale) {
                kinds.add(containsAny(message, "详情", "售后工单")
                        ? "after-sale-detail" : "after-sale-list");
            }
            if (customer) {
                kinds.add("customer-list");
            }
            if (order && (!afterSale || !message.contains("的售后"))) {
                kinds.add("order-list");
            }
            if (product) {
                kinds.add("product-list");
            }
        }
        return Set.copyOf(kinds);
    }

    private boolean isKnowledgeQuestion(String message) {
        return containsAny(
                message, "是什么意思", "什么含义", "怎么理解", "介绍一下",
                "解释一下", "有哪些状态", "规则是什么");
    }

    private String find(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int count(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) count++;
        }
        return count;
    }
}
