package com.xjjk.agent.order.tool;

import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.ShipmentTimeline;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 暴露给模型的订单与物流只读工具。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTools {

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_MODEL_TEXT_LENGTH = 1900;

    private final OrderQueryGateway gateway;

    /**
     * 按完整业务编号精确查询订单。
     *
     * <p>模型只得到有界摘要；下游返回的完整脱敏订单卡片通过 SSE 直接交给前端。</p>
     */
    @Tool(name = "search_orders", description = "按完整订单号、外部订单号或运单号精确查询订单。用户提供了完整业务编号并询问订单信息时调用。")
    public String searchOrders(
            @ToolParam(description = "完整订单号、外部订单号或运单号") String identifier,
            @ToolParam(description = "匹配类型：AUTO、ORDER_CODE、OUTER_ORDER_CODE、LOGISTICS_CODE；不确定时用AUTO", required = false)
            String identifierType,
            ToolContext toolContext) {
        // 先规范化模型参数，错误参数直接返回可理解提示，不访问下游也不占用调用额度。
        ParsedArguments arguments = parseArguments(identifier, identifierType);
        if (arguments.errorMessage() != null) {
            // 参数错误不进入 Guard，也不消耗单轮工具额度，更不会访问下游。
            return arguments.errorMessage();
        }
        AgentToolRequestContext requestContext = requestContext(toolContext);
        try {
            // 工具名和规范化参数共同组成单轮幂等键，避免模型并发重复查询同一订单。
            return requestContext.callGuard().execute(
                    "search_orders",
                    arguments.canonicalKey(),
                    () -> executeSearch(arguments, requestContext));
        } catch (OrderServiceUnavailableException exception) {
            // 下游异常必须先穿过 Guard，失败键才能删除并归还额度；之后才转换为安全提示。
            return "订单查询服务暂时不可用，请稍后重试。";
        }
    }

    /** 按完整业务编号查询订单对应的多运单物流时间线。 */
    @Tool(name = "get_order_logistics", description = "按完整订单号、外部订单号或运单号查询物流最新状态。用户提供了完整业务编号并询问物流、配送或轨迹时调用。")
    public String getOrderLogistics(
            @ToolParam(description = "完整订单号、外部订单号或运单号") String identifier,
            @ToolParam(description = "匹配类型：AUTO、ORDER_CODE、OUTER_ORDER_CODE、LOGISTICS_CODE；不确定时用AUTO", required = false)
            String identifierType,
            ToolContext toolContext) {
        // 订单查询与物流查询使用相同的编号规范，但使用不同工具名形成不同幂等键。
        ParsedArguments arguments = parseArguments(identifier, identifierType);
        if (arguments.errorMessage() != null) {
            return arguments.errorMessage();
        }
        AgentToolRequestContext requestContext = requestContext(toolContext);
        try {
            return requestContext.callGuard().execute(
                    "get_order_logistics",
                    arguments.canonicalKey(),
                    () -> executeLogistics(arguments, requestContext));
        } catch (OrderServiceUnavailableException exception) {
            return "物流查询服务暂时不可用，请稍后重试。";
        }
    }

    private String executeSearch(
            ParsedArguments arguments,
            AgentToolRequestContext requestContext) {
        try {
            // 认证身份和 requestId 只从服务端 ToolContext 获取，模型参数不能覆盖可信请求头。
            OrderSearchResult result = gateway.search(
                    arguments.identifier(),
                    arguments.type(),
                    requestContext.identity(),
                    requestContext.requestId());
            // 完整结果通过服务端可信发布器输出；成功值由 Guard 保留，重复调用不会重复发布。
            requestContext.outputPublisher().publish(new ToolUiResult(
                    "search_orders", "order-list", 1, result.queriedAt(), result));
            return toSearchModelText(result);
        } catch (OrderServiceUnavailableException exception) {
            log.warn("order_tool_failed requestId={}, operation=search_orders, exceptionType={}",
                    requestContext.requestId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String executeLogistics(
            ParsedArguments arguments,
            AgentToolRequestContext requestContext) {
        try {
            // Gateway 负责下游鉴权、重试、熔断和响应校验，返回已脱敏的物流领域结果。
            OrderLogisticsResult result = gateway.logistics(
                    arguments.identifier(),
                    arguments.type(),
                    requestContext.identity(),
                    requestContext.requestId());
            requestContext.outputPublisher().publish(new ToolUiResult(
                    "get_order_logistics", "logistics-timeline", 1,
                    result.queriedAt(), result));
            return toLogisticsModelText(result);
        } catch (OrderServiceUnavailableException exception) {
            log.warn("order_tool_failed requestId={}, operation=get_order_logistics, exceptionType={}",
                    requestContext.requestId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /**
     * 校验并规范化模型提供的业务编号及匹配类型。
     *
     * <p>返回对象同时携带真实查询参数、单轮去重键和安全错误文案，防止三者使用
     * 不同规范化规则。内部的 CUSTOMER 类型不会暴露给模型调用。</p>
     */
    private ParsedArguments parseArguments(String identifier, String identifierType) {
        if (identifier == null) {
            return ParsedArguments.error("请提供完整的订单号、外部订单号或运单号。");
        }
        String normalizedIdentifier = identifier.strip();
        if (normalizedIdentifier.isEmpty()
                || normalizedIdentifier.length() > MAX_IDENTIFIER_LENGTH
                || normalizedIdentifier.codePoints().anyMatch(Character::isISOControl)) {
            return ParsedArguments.error("业务编号不合法，请提供不超过128个字符且不含控制字符的完整编号。");
        }

        String normalizedType = identifierType == null || identifierType.isBlank()
                ? OrderIdentifierType.AUTO.name()
                : identifierType.strip().toUpperCase(Locale.ROOT);
        OrderIdentifierType type;
        try {
            type = OrderIdentifierType.valueOf(normalizedType);
        } catch (IllegalArgumentException exception) {
            return ParsedArguments.error(
                    "匹配类型不支持，请使用 AUTO、ORDER_CODE、OUTER_ORDER_CODE 或 LOGISTICS_CODE。");
        }
        // CUSTOMER 只允许由服务端“客户编码 -> 可信客户 ID”链路内部使用，绝不能成为模型可传参数。
        if (type == OrderIdentifierType.CUSTOMER) {
            return ParsedArguments.error(
                    "匹配类型不支持，请使用 AUTO、ORDER_CODE、OUTER_ORDER_CODE 或 LOGISTICS_CODE。");
        }
        // 去重键和真正传给 Gateway 的编号共用同一规范化结果，防止缓存语义与真实查询分叉。
        return ParsedArguments.valid(
                normalizedIdentifier, type,
                type.name() + "|" + normalizedIdentifier);
    }

    /** 提取服务端注入的本轮身份和输出通道，缺失时严格失败而不是匿名调用。 */
    private AgentToolRequestContext requestContext(ToolContext context) {
        if (context == null) {
            throw new IllegalStateException("订单工具缺少请求上下文");
        }
        Object value = context.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext requestContext)) {
            throw new IllegalStateException("订单工具请求上下文不合法");
        }
        return requestContext;
    }

    /** 生成供模型概括的订单最小事实；完整金额、商品和收货信息只走前端卡片。 */
    private String toSearchModelText(OrderSearchResult result) {
        String queriedAt = String.valueOf(result.queriedAt());
        if (result.items().isEmpty()) {
            return bounded("查询时间=" + queriedAt
                    + "。未查询到匹配订单，前端已展示空结果。请简洁告知用户并核对完整业务编号。");
        }
        StringBuilder text = new StringBuilder("查询时间=")
                .append(queriedAt)
                .append("。前端已展示")
                .append(result.items().size())
                .append("条订单卡片（匹配总数=")
                .append(result.total())
                .append("）。请基于以下最小事实简洁回答，不要逐条复述卡片：");
        for (OrderCard item : result.items()) {
            text.append(" 订单号=").append(abbreviate(item.orderCode(), 96))
                    .append("，状态=").append(abbreviate(item.statusText(), 48))
                    .append("，商品数量=").append(item.goodsTotalCount()).append('；');
        }
        if (result.truncated()) {
            text.append("结果已截断，如需定位请让用户提供更精确编号。");
        }
        return bounded(text.toString());
    }

    /** 生成供模型回答最新状态的物流摘要，完整轨迹仍由结构化卡片展示。 */
    private String toLogisticsModelText(OrderLogisticsResult result) {
        StringBuilder text = new StringBuilder("查询时间=")
                .append(result.queriedAt())
                .append("。订单号=")
                .append(abbreviate(result.order().orderCode(), 96))
                .append("，订单状态=")
                .append(abbreviate(result.order().statusText(), 48))
                .append("。前端已展示完整物流时间线；请只概括最新状态，不要复述全部轨迹。");
        if (result.shipments().isEmpty()) {
            text.append("当前未查询到运单轨迹。");
        }
        for (ShipmentTimeline shipment : result.shipments()) {
            text.append(" 运单号=").append(abbreviate(shipment.logisticsCode(), 96))
                    .append("，最新状态=").append(abbreviate(shipment.latestStatusText(), 64))
                    .append("，最新轨迹=").append(abbreviate(shipment.latestTrace(), 160)).append('；');
        }
        if (result.partial()) {
            text.append("本次为部分物流结果，请明确提示用户结果可能不完整。");
        }
        return bounded(text.toString());
    }

    /** 对整个模型可见文本施加硬上限，避免异常下游数据放大上下文。 */
    private String bounded(String value) {
        return value.length() < MAX_MODEL_TEXT_LENGTH
                ? value
                : value.substring(0, MAX_MODEL_TEXT_LENGTH - 1) + "…";
    }

    /** 清理控制字符并限制单个字段长度，日志和模型都不会接收无界业务文本。 */
    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength - 1) + "…";
    }

    /** 已规范化的工具参数和值对象，错误结果不会携带可执行字段。 */
    private record ParsedArguments(
            String identifier,
            OrderIdentifierType type,
            String canonicalKey,
            String errorMessage) {

        private static ParsedArguments valid(
                String identifier,
                OrderIdentifierType type,
                String canonicalKey) {
            return new ParsedArguments(identifier, type, canonicalKey, null);
        }

        private static ParsedArguments error(String errorMessage) {
            return new ParsedArguments(null, null, null, errorMessage);
        }
    }
}
