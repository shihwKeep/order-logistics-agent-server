package com.xjjk.agent.customer.tool;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 暴露给模型的“按客户编号查询订单”只读工具。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerOrderQueryTools {
    private static final int MAX_CUSTOMER_CODE_LENGTH = 128;
    private static final int MAX_MODEL_TEXT_LENGTH = 1900;

    private final CustomerOrderQueryService service;

    @Tool(name = "list_customer_orders",
            description = "按完整客户编号查询当前坐席有权访问的该客户订单列表。只有用户明确提供客户编号并询问该客户订单时调用。")
    public String listCustomerOrders(
            @ToolParam(description = "完整客户编号") String customerCode,
            ToolContext toolContext) {
        String validationError = validate(customerCode);
        if (validationError != null) {
            return validationError;
        }
        String normalizedCode = customerCode.strip();
        AgentToolRequestContext context = requestContext(toolContext);
        try {
            return context.callGuard().execute(
                    "list_customer_orders",
                    normalizedCode,
                    () -> execute(normalizedCode, context));
        } catch (CustomerServiceUnavailableException | OrderServiceUnavailableException exception) {
            return "客户订单查询服务暂时不可用，请稍后重试。";
        }
    }

    private String execute(String customerCode, AgentToolRequestContext context) {
        try {
            CustomerOrderQueryResult result = service.query(
                    customerCode, context.identity(), context.requestId());
            return switch (result.resolution()) {
                case NOT_FOUND -> "未查询到客户编号=" + customerCode
                        + " 的客户，请让用户核对完整客户编号。";
                case AMBIGUOUS -> "客户编号=" + customerCode
                        + " 无法唯一定位客户，未执行订单查询，请让用户核对客户编号。";
                case FOUND -> publishAndDescribe(result, context);
            };
        } catch (CustomerServiceUnavailableException | OrderServiceUnavailableException exception) {
            log.warn("customer_order_tool_failed requestId={}, exceptionType={}",
                    context.requestId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String publishAndDescribe(
            CustomerOrderQueryResult result,
            AgentToolRequestContext context) {
        OrderSearchResult orders = result.orders();
        // 复用订单卡片协议；安全结果中已经没有 customerId。
        context.outputPublisher().publish(new ToolUiResult(
                "list_customer_orders", "order-list", 1, orders.queriedAt(), orders));
        if (orders.items().isEmpty()) {
            return bounded("客户编号=" + result.customerCode()
                    + "，客户名称=" + safe(result.customerDisplayName(), 64)
                    + "。未查询到订单，前端已展示空结果。");
        }
        StringBuilder text = new StringBuilder("客户编号=")
                .append(result.customerCode())
                .append("，客户名称=")
                .append(safe(result.customerDisplayName(), 64))
                .append("。前端已展示")
                .append(orders.items().size())
                .append("条订单卡片（匹配总数=")
                .append(orders.total())
                .append("）。请简洁概括，不要逐条复述卡片：");
        for (OrderCard item : orders.items()) {
            text.append(" 订单号=").append(safe(item.orderCode(), 96))
                    .append("，状态=").append(safe(item.statusText(), 48))
                    .append("，商品数量=").append(item.goodsTotalCount()).append('；');
        }
        return bounded(text.toString());
    }

    private String validate(String customerCode) {
        if (customerCode == null || customerCode.isBlank()) {
            return "请提供完整客户编号。";
        }
        String value = customerCode.strip();
        if (value.length() > MAX_CUSTOMER_CODE_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return "客户编号不合法，请提供不超过128个字符且不含控制字符的完整编号。";
        }
        return null;
    }

    private AgentToolRequestContext requestContext(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalStateException("客户订单工具缺少请求上下文");
        }
        Object value = toolContext.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext context)) {
            throw new IllegalStateException("客户订单工具请求上下文不合法");
        }
        return context;
    }

    private String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength - 1) + "…";
    }

    private String bounded(String value) {
        return value.length() <= MAX_MODEL_TEXT_LENGTH
                ? value : value.substring(0, MAX_MODEL_TEXT_LENGTH - 1) + "…";
    }
}
