package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.config.BusinessQueryEnforcementProperties;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent;
import com.xjjk.agent.chat.orchestration.CompositeQueryPlan;
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

    /**
     * 把当前用户原文分类为普通问答、确定性直查或模型工具查询。
     *
     * <p>这里只做执行安全分流，不承担完整语义理解。规则不能唯一确定参数时，
     * 仍由模型依据工具描述选择工具，但结果必须经过本轮新鲜性门禁。</p>
     */
    public BusinessQueryPlan plan(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return BusinessQueryPlan.general();
        }
        String message = rawMessage.strip();
        if (isKnowledgeQuestion(message)) {
            // “订单状态有哪些”是在问租户业务知识，不代表要求读取某一笔实时订单。
            // 进入缓冲路径后，只有本轮知识检索返回可靠证据才允许模型正文流出。
            return BusinessQueryPlan.modelRequired(Set.of("knowledge-citations"));
        }
        // 业务实时查询总开关不关闭知识回答的证据门禁。
        if (!properties.enabled()) {
            return BusinessQueryPlan.general();
        }

        // 关键词只用于判断涉及哪些业务域，不直接作为下游查询参数。
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

        // 复合问题先进入显式工作流，避免实时查询和知识库规则被模型混在一次工具循环中。
        CompositeQueryPlan compositePlan = compositePlanFor(
                message, product, customer, order, logistics, afterSale);
        if (compositePlan != null) {
            return BusinessQueryPlan.composite(compositePlan);
        }

        int domains = count(product, customer, logistics, afterSale);
        // 单一物流意图且能提取完整订单号时，直接执行固定物流动作，减少一次模型决策。
        if (domains == 1 && logistics) {
            String code = find(ORDER_CODE, message);
            if (code != null) {
                return BusinessQueryPlan.direct(
                        new ChatActionRequest("QUERY_ORDER_LOGISTICS", code),
                        "logistics-timeline");
            }
        }
        // “客户 + 订单”且客户编号唯一可提取时，直接走客户订单白名单动作。
        if (domains == 1 && customer && order) {
            String code = find(CUSTOMER_CODE, message);
            if (code != null) {
                return BusinessQueryPlan.direct(
                        new ChatActionRequest("QUERY_CUSTOMER_ORDERS", null, code),
                        "order-list");
            }
        }
        // 明确售后工单详情且只出现售后域时，允许从当前消息提取工单号直接查询。
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

        // 无法安全直查时只声明允许出现的结果类型，具体工具及参数交给模型选择。
        Set<String> acceptedKinds = acceptedKinds(
                product, customer, order, logistics, afterSale, message);
        return acceptedKinds.isEmpty()
                ? BusinessQueryPlan.general()
                : BusinessQueryPlan.modelRequired(acceptedKinds);
    }

    /** 只为已能安全提取公开业务标识的组合问题创建复合计划。 */
    private CompositeQueryPlan compositePlanFor(
            String message,
            boolean product,
            boolean customer,
            boolean order,
            boolean logistics,
            boolean afterSale) {
        if (!containsAny(message, "规则", "政策", "制度", "流程", "规范", "阈值",
                "判断是否", "是否需要", "如何处理", "怎么处理", "是否符合")) {
            return null;
        }
        String orderCode = find(ORDER_CODE, message);
        if (logistics && orderCode != null) {
            return CompositeQueryPlan.of(java.util.List.of(
                    CompositeQueryIntent.logistics(orderCode),
                    CompositeQueryIntent.knowledge(message)));
        }
        if (order && orderCode != null) {
            return CompositeQueryPlan.of(java.util.List.of(
                    CompositeQueryIntent.order(orderCode),
                    CompositeQueryIntent.knowledge(message)));
        }
        String customerCode = find(CUSTOMER_CODE, message);
        if (customer && order && customerCode != null) {
            return CompositeQueryPlan.of(java.util.List.of(
                    CompositeQueryIntent.customerOrders(customerCode),
                    CompositeQueryIntent.knowledge(message)));
        }
        String afterSaleCode = find(AFTER_SALE_CODE, message);
        if (afterSale && afterSaleCode != null) {
            return CompositeQueryPlan.of(java.util.List.of(
                    CompositeQueryIntent.afterSale(afterSaleCode),
                    CompositeQueryIntent.knowledge(message)));
        }
        return null;
    }

    /** 根据本轮涉及的业务域生成结构化结果类型白名单。 */
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

    /** 排除只解释概念和规则、不需要访问实时业务系统的问题。 */
    private boolean isKnowledgeQuestion(String message) {
        // 带完整业务编号的“当前状态/详情”问题属于实时查询，不能因为包含“状态”
        // 等词被误送到知识库；商品库存和价格同理。
        boolean identifiedRealtimeQuery = (find(ORDER_CODE, message) != null
                || find(CUSTOMER_CODE, message) != null
                || find(AFTER_SALE_CODE, message) != null)
                && containsAny(message, "查询", "查看", "查下", "查一下", "帮我查",
                "帮忙查", "当前", "现在", "详情", "状态", "物流", "轨迹", "进度");
        boolean productRealtimeQuery = containsAny(message, "商品", "SKU", "sku", "库存", "上下架")
                && containsAny(message, "查询", "查看", "查下", "查一下", "帮我查",
                "帮忙查", "还有", "多少", "有没有", "价格", "多少钱", "吗");
        if (identifiedRealtimeQuery || productRealtimeQuery) {
            return false;
        }

        boolean explicitKnowledgeQuestion = containsAny(
                message, "是什么意思", "什么含义", "怎么理解", "介绍一下",
                "解释一下", "有哪些状态", "规则是什么", "规定是什么",
                "政策", "制度", "操作规范", "处理规范", "什么流程", "如何处理");
        boolean knowledgeDomain = containsAny(
                message, "退款", "退货", "换货", "售后", "签收", "发货", "配送",
                "物流", "订单", "商品", "库存", "客户", "会员", "支付", "收款",
                "运费", "发票");
        boolean asksRuleOrBoundary = containsAny(
                message, "期限", "多久", "几天", "条件", "情况", "是否可以", "能不能",
                "可以吗", "怎么办", "怎么", "怎样", "怎么算", "如何", "哪些", "为什么", "要求",
                "标准", "规则", "规定", "政策", "制度", "流程", "规范", "含义", "意思",
                // “赔偿金额/赔付标准是多少”属于企业政策问答；没有业务编号时不能误送实时查询。
                "赔偿", "赔付", "补偿", "金额", "额度");
        // “介绍一下你自己”等普通对话不能仅因问句样式被送入企业知识库。
        return knowledgeDomain && (explicitKnowledgeQuestion || asksRuleOrBoundary);
    }

    /** 从当前用户消息中提取第一个符合严格边界的完整业务编号。 */
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
