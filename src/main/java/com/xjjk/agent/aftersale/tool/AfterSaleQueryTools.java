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
    /** 售后工单号、订单号、客户编号或姓名统一使用的输入长度上限。 */
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    /** 只限制模型可见摘要；完整售后卡片使用独立结构化结果通道。 */
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
        // 先统一校验匹配类型、查询值和可选时间范围，错误参数不会访问下游。
        ParsedSearch arguments = parseSearch(identifierType, identifier, startTime, endTime);
        if (arguments.error() != null) {
            return arguments.error();
        }
        AgentToolRequestContext context = requestContext(toolContext);
        // 即使回调因配置漂移被意外注册，工具内部仍做一次可信身份下的能力校验。
        if (!availability.isSearchAvailable(context.identity())) {
            return "当前组织暂未开放售后查询能力。";
        }
        try {
            // 匹配类型、规范化标识和时间范围共同组成单轮幂等键。
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
        // 详情只接受单一完整工单号，不允许模型传递售后内部主键。
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
            // 可信身份和 requestId 均来自 ToolContext；Gateway 负责下游鉴权和防腐转换。
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
            // 完整详情先发布为前端结构化结果，模型只接收下方生成的最小概括文本。
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

    /**
     * 规范化售后查询参数并构造稳定去重键。
     *
     * <p>时间必须是带时区的 ISO-8601，且开始时间不能晚于结束时间。</p>
     */
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

    /** 解析可选时间；空文本表示该侧时间范围不设限。 */
    private OffsetDateTime parseTime(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value.strip());
    }

    /** 清理并限制通用查询标识，控制字符在进入下游前被拒绝。 */
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

    /** 从非提示词 ToolContext 中提取服务端可信工具上下文。 */
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

    /** 对模型可见的售后摘要施加总长度上限。 */
    private String bounded(String value) {
        return value.length() <= MAX_MODEL_TEXT_LENGTH
                ? value : value.substring(0, MAX_MODEL_TEXT_LENGTH - 1) + "…";
    }

    /** 清理控制字符并缩短单个字段，避免异常下游文本污染模型上下文。 */
    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength - 1) + "…";
    }

    /** 已校验的售后搜索参数；错误对象只携带安全提示。 */
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
