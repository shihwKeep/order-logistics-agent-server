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
    /** 限制模型侧摘要长度；前端结构化客户卡片不受这段文本长度影响。 */
    private static final int MAX_MODEL_TEXT = 1600;
    private final CustomerQueryGateway gateway;

    @Tool(name = "search_customers")
    public String searchCustomers(
            @ToolParam String keyword,
            @ToolParam(required = false)
            String matchType,
            ToolContext toolContext) {
        // 模型参数先在本地规范化，错误输入不进入 Guard，也不会调用客户服务。
        Parsed parsed = parse(keyword, matchType);
        if (parsed.error() != null) {
            return parsed.error();
        }
        AgentToolRequestContext context = requestContext(toolContext);
        try {
            // 同一轮相同匹配类型和关键词只执行一次真实查询，并复用首次成功文本。
            return context.callGuard().execute("search_customers", parsed.key(), () -> {
                try {
                    // Gateway 注入可信坐席身份并把下游响应映射为已脱敏领域对象。
                    CustomerSearchResult result = gateway.search(parsed.keyword(), parsed.type(),
                            context.identity(), context.requestId());
                    // UI 获取完整卡片协议，模型仅获得下方 modelText 生成的最小事实。
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

    /** 规范化客户关键词和匹配类型，并生成与真实请求语义一致的去重键。 */
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

    /** 将查询结果压缩成模型可见的最小事实，并限制总长度。 */
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

    /** 从领域结果移除仅供后端关联使用的 customerId，形成可持久化的前端协议。 */
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

    /** 从 Spring AI ToolContext 中恢复服务端注入的可信身份和结果发布器。 */
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

    /** 客户工具的规范化参数；存在 error 时其余字段不可用于下游查询。 */
    private record Parsed(String keyword, CustomerMatchType type, String key, String error) {
        static Parsed error(String value) {
            return new Parsed(null, null, null, value);
        }
    }


    /** 前端客户列表卡片协议，不包含内部客户主键。 */
    private record CustomerListPayload(
            CustomerMatchType matchedBy,
            long total,
            boolean truncated,
            OffsetDateTime queriedAt,
            List<CustomerCardPayload> items) {
    }

    /** 单个脱敏客户卡片，只保留坐席界面需要展示的字段。 */
    private record CustomerCardPayload(
            String customerCode,
            String displayName,
            String gradeName,
            String assetTypeName,
            String customerTypeName) {
    }
}
