package com.xjjk.agent.customer.tool;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.customer.service.CustomerQueryGateway;
import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.time.OffsetDateTime;
import java.util.List;

/** 暴露给模型的客户只读工具。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerQueryTools {
    private static final int MAX_MODEL_TEXT = 1600;
    private final CustomerQueryGateway gateway;

    @Tool(name = "search_customers",
            description = "按完整客户编号或完整客户姓名查询当前坐席有权访问的客户。姓名重名时返回列表供用户确认。")
    public String searchCustomers(
            @ToolParam(description = "完整客户编号或完整客户姓名") String keyword,
            @ToolParam(description = "AUTO、CUSTOMER_CODE、CUSTOMER_NAME；不确定时用AUTO", required = false)
            String matchType,
            ToolContext toolContext) {
        Parsed parsed = parse(keyword, matchType);
        if (parsed.error() != null) {
            return parsed.error();
        }
        AgentToolRequestContext context = requestContext(toolContext);
        try {
            return context.callGuard().execute("search_customers", parsed.key(), () -> {
                try {
                    CustomerSearchResult result = gateway.search(parsed.keyword(), parsed.type(),
                            context.identity(), context.requestId());
                    context.outputPublisher().publish(new ToolUiResult(
                            "search_customers", "customer-list", 1, result.queriedAt(),
                            toUiPayload(result)));
                    return modelText(result);
                } catch (CustomerServiceUnavailableException exception) {
                    log.warn("customer_tool_failed requestId={}, exceptionType={}",
                            context.requestId(), exception.getClass().getSimpleName());
                    throw exception;
                }
            });
        } catch (CustomerServiceUnavailableException exception) {
            return "客户查询服务暂时不可用，请稍后重试。";
        }
    }

    private Parsed parse(String keyword, String rawType) {
        if (keyword == null || keyword.isBlank()) {
            return Parsed.error("请提供完整客户编号或完整客户姓名。");
        }
        String normalized = keyword.strip();
        if (normalized.length() > 128
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return Parsed.error("客户查询内容不合法，请重新提供。");
        }
        String typeValue = rawType == null || rawType.isBlank()
                ? CustomerMatchType.AUTO.name() : rawType.strip().toUpperCase(Locale.ROOT);
        try {
            CustomerMatchType type = CustomerMatchType.valueOf(typeValue);
            return new Parsed(normalized, type, type.name() + "|" + normalized, null);
        } catch (IllegalArgumentException exception) {
            return Parsed.error("匹配类型不支持，请使用 AUTO、CUSTOMER_CODE 或 CUSTOMER_NAME。");
        }
    }

    private String modelText(CustomerSearchResult result) {
        if (result.items().isEmpty()) {
            return "未查询到匹配客户，前端已展示空结果。请让用户核对完整客户编号或姓名。";
        }
        StringBuilder text = new StringBuilder("前端已展示")
                .append(result.items().size()).append("条客户卡片。请简洁回答，不要猜测客户敏感信息：");
        for (CustomerSearchItem item : result.items()) {
            // 内部 customerId 仅存在于后端领域结果，绝不进入模型文本、SSE 或历史消息。
            text.append(" 客户编号=").append(item.customerCode())
                    .append("，客户名称=").append(item.displayName())
                    .append("，等级=").append(item.gradeName())
                    .append("，类型=").append(item.customerTypeName()).append('；');
        }
        String value = text.toString();
        return value.length() <= MAX_MODEL_TEXT ? value
                : value.substring(0, MAX_MODEL_TEXT - 1) + "…";
    }

    private CustomerListPayload toUiPayload(CustomerSearchResult result) {
        // customerId 只服务于后端可信解析链路，SSE 与历史快照都只保留可再次解析的客户编号。
        List<CustomerCardPayload> items = result.items().stream()
                .map(item -> new CustomerCardPayload(
                        item.customerCode(),
                        item.displayName(),
                        item.gradeName(),
                        item.assetTypeName(),
                        item.customerTypeName()))
                .toList();
        return new CustomerListPayload(
                result.matchedBy(), result.total(), result.truncated(), result.queriedAt(), items);
    }

    private AgentToolRequestContext requestContext(ToolContext context) {
        if (context == null) {
            throw new IllegalStateException("客户工具缺少请求上下文");
        }
        Object value = context.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext requestContext)) {
            throw new IllegalStateException("客户工具请求上下文不合法");
        }
        return requestContext;
    }

    private record Parsed(String keyword, CustomerMatchType type, String key, String error) {
        static Parsed error(String value) {
            return new Parsed(null, null, null, value);
        }
    }


    private record CustomerListPayload(
            CustomerMatchType matchedBy,
            long total,
            boolean truncated,
            OffsetDateTime queriedAt,
            List<CustomerCardPayload> items) {
    }

    private record CustomerCardPayload(
            String customerCode,
            String displayName,
            String gradeName,
            String assetTypeName,
            String customerTypeName) {
    }
}
