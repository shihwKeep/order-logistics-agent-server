package com.xjjk.agent.aftersale.tool;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** 暴露给模型的售后只读工具；完整结果只通过 SSE 发送给前端。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AfterSaleQueryTools {
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_MODEL_TEXT_LENGTH = 1900;

    private final AfterSaleQueryGateway gateway;
    private final AfterSaleToolAvailability availability;

    @Tool(name = "search_after_sales",
            description = "按售后工单号、原订单号、客户编号或客户姓名查询售后工单；可选售后创建时间范围。")
    public String searchAfterSales(
            @ToolParam(description = "AFTER_SALE_CODE、ORDER_CODE、CUSTOMER_CODE、CUSTOMER_NAME")
            String identifierType,
            @ToolParam(description = "对应的售后工单号、原订单号、客户编号或客户姓名")
            String identifier,
            @ToolParam(description = "可选开始时间，ISO-8601", required = false)
            String startTime,
            @ToolParam(description = "可选结束时间，ISO-8601", required = false)
            String endTime,
            ToolContext toolContext) {
        ParsedSearch arguments = parseSearch(identifierType, identifier, startTime, endTime);
        if (arguments.error() != null) {
            return arguments.error();
        }
        AgentToolRequestContext context = requestContext(toolContext);
        if (!availability.isSearchAvailable(context.identity())) {
            return "当前组织暂未开放售后查询能力。";
        }
        try {
            return context.callGuard().execute(
                    "search_after_sales", arguments.key(),
                    () -> executeSearch(arguments, context));
        } catch (AfterSaleServiceUnavailableException exception) {
            return "售后查询服务暂时不可用，请稍后重试。";
        }
    }

    @Tool(name = "get_after_sale_detail",
            description = "按完整售后工单号查询售后详情、商品与退款汇总。")
    public String getAfterSaleDetail(
            @ToolParam(description = "完整售后工单号") String afterSaleCode,
            ToolContext toolContext) {
        String normalized = normalizeCode(afterSaleCode);
        if (normalized == null) {
            return "请提供不超过128个字符且不含控制字符的完整售后工单号。";
        }
        AgentToolRequestContext context = requestContext(toolContext);
        if (!availability.isDetailAvailable(context.identity())) {
            return "当前组织暂未开放售后详情能力。";
        }
        try {
            return context.callGuard().execute(
                    "get_after_sale_detail", normalized,
                    () -> executeDetail(normalized, context));
        } catch (AfterSaleServiceUnavailableException exception) {
            return "售后详情服务暂时不可用，请稍后重试。";
        }
    }

    private String executeSearch(ParsedSearch arguments, AgentToolRequestContext context) {
        try {
            AfterSaleSearchResult result = gateway.search(
                    arguments.type(), arguments.identifier(),
                    arguments.startTime(), arguments.endTime(),
                    context.identity(), context.requestId());
            // 卡片所需的完整脱敏数据绕过模型文本，直接作为结构化 SSE 结果发布。
            context.outputPublisher().publish(new ToolUiResult(
                    "search_after_sales", "after-sale-list", 1,
                    result.queriedAt(), result));
            return boundedSearchText(result);
        } catch (AfterSaleServiceUnavailableException exception) {
            log.warn("after_sale_tool_failed requestId={}, operation=search_after_sales, exceptionType={}",
                    context.requestId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String executeDetail(String afterSaleCode, AgentToolRequestContext context) {
        try {
            AfterSaleDetailResult result = gateway.detail(
                    afterSaleCode, context.identity(), context.requestId());
            context.outputPublisher().publish(new ToolUiResult(
                    "get_after_sale_detail", "after-sale-detail", 1,
                    result.queriedAt(), result));
            return boundedDetailText(result);
        } catch (AfterSaleServiceUnavailableException exception) {
            log.warn("after_sale_tool_failed requestId={}, operation=get_after_sale_detail, exceptionType={}",
                    context.requestId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /** 模型只获得回答所需最小事实，不注入售后说明、商品原因或退款金额。 */
    private String boundedSearchText(AfterSaleSearchResult result) {
        StringBuilder text = new StringBuilder("查询时间=")
                .append(result.queriedAt())
                .append("。前端已展示")
                .append(result.items().size())
                .append("条售后卡片（匹配总数=")
                .append(result.total()).append("）。");
        if (result.items().isEmpty()) {
            text.append("未查询到匹配售后工单，请让用户核对查询条件。");
        } else {
            text.append("请基于以下最小事实简洁回答，不要逐条复述卡片：");
            for (AfterSaleSearchResult.Item item : result.items()) {
                text.append(" 售后工单号=").append(abbreviate(item.afterSaleCode(), 96))
                        .append("，状态=").append(abbreviate(item.statusText(), 48)).append('；');
            }
        }
        if (result.truncated()) {
            text.append("结果已截断，请让用户提供更精确条件或时间范围。");
        }
        return bounded(text.toString());
    }

    private String boundedDetailText(AfterSaleDetailResult result) {
        return bounded("查询时间=" + result.queriedAt()
                + "。售后工单号=" + abbreviate(result.afterSaleCode(), 96)
                + "，状态=" + abbreviate(result.statusText(), 48)
                + "，商品行数=" + result.items().size()
                + "，换货商品行数=" + result.exchangeGoods().size()
                + "。前端已展示详情，请简洁概括，不要复述售后说明、商品原因或退款明细。");
    }

    private ParsedSearch parseSearch(
            String rawType, String rawIdentifier, String rawStartTime, String rawEndTime) {
        String identifier = normalizeCode(rawIdentifier);
        if (identifier == null) {
            return ParsedSearch.error("请提供不超过128个字符且不含控制字符的查询内容。");
        }
        AfterSaleIdentifierType type;
        try {
            type = AfterSaleIdentifierType.valueOf(
                    rawType == null ? "" : rawType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ParsedSearch.error(
                    "匹配类型不支持，请使用 AFTER_SALE_CODE、ORDER_CODE、CUSTOMER_CODE 或 CUSTOMER_NAME。");
        }
        if (type == AfterSaleIdentifierType.CUSTOMER_NAME
                && identifier.codePointCount(0, identifier.length()) < 2) {
            return ParsedSearch.error("客户姓名至少需要两个字符。");
        }
        OffsetDateTime startTime;
        OffsetDateTime endTime;
        try {
            startTime = parseTime(rawStartTime);
            endTime = parseTime(rawEndTime);
        } catch (DateTimeParseException exception) {
            return ParsedSearch.error("时间格式不正确，请提供带时区的 ISO-8601 时间。");
        }
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            return ParsedSearch.error("开始时间不能晚于结束时间。");
        }
        String key = type.name() + '|' + identifier + '|'
                + String.valueOf(startTime) + '|' + String.valueOf(endTime);
        return new ParsedSearch(type, identifier, startTime, endTime, key, null);
    }

    private OffsetDateTime parseTime(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value.strip());
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_IDENTIFIER_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private AgentToolRequestContext requestContext(ToolContext context) {
        if (context == null) {
            throw new IllegalStateException("售后工具缺少请求上下文");
        }
        Object value = context.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext requestContext)) {
            throw new IllegalStateException("售后工具请求上下文不合法");
        }
        return requestContext;
    }

    private String bounded(String value) {
        return value.length() <= MAX_MODEL_TEXT_LENGTH
                ? value : value.substring(0, MAX_MODEL_TEXT_LENGTH - 1) + "…";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength - 1) + "…";
    }

    private record ParsedSearch(
            AfterSaleIdentifierType type,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String key,
            String error) {
        private static ParsedSearch error(String message) {
            return new ParsedSearch(null, null, null, null, null, message);
        }
    }
}
