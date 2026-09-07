package com.xjjk.agent.product.tool;

import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.product.service.ProductSearchUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 暴露给模型的商品只读工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductQueryTools {
    /** 工具每次固定返回十条，避免模型通过参数放大下游查询和上下文开销。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ProductSearchGateway gateway;

    /**
     * 完整数据发布给前端，模型只接收去掉图片地址后的紧凑文本，控制 Token 开销。
     */
    @Tool(name = "search_products", description = "根据商品名称、SPU编码、SKU编码或条码查询商品列表、规格、价格、库存和上下架状态。用户询问具体商品信息时必须调用。")
    public String searchProducts(
            @ToolParam(description = "商品名称、SPU编码、SKU编码或条码") String keyword,
            @ToolParam(description = "页码，从1开始", required = false) Integer pageIndex,
            ToolContext toolContext) {
        AgentToolRequestContext requestContext = requestContext(toolContext);
        ProductSearchQuery query;
        try {
            query = ProductSearchQuery.of(keyword, pageIndex, DEFAULT_PAGE_SIZE);
        } catch (IllegalArgumentException exception) {
            return "商品查询参数不完整，请让用户提供商品名称或商品编码。";
        }

        String canonicalArguments = query.keyword()
                + "|" + query.pageIndex()
                + "|" + DEFAULT_PAGE_SIZE;
        return requestContext.callGuard().execute(
                "search_products",
                canonicalArguments,
                () -> executeSearch(query, requestContext));
    }

    private String executeSearch(
            ProductSearchQuery query,
            AgentToolRequestContext requestContext
    ) {
        try {
            ProductSearchResult result = gateway.search(query);
            // 完整结果只走服务端注入的发布器；成功结果由 Guard 缓存，
            // 因而同参重复工具调用不会再次查询下游或重复发布 SSE。
            requestContext.outputPublisher().publish(new ToolUiResult(
                    "search_products",
                    "product-list",
                    1,
                    OffsetDateTime.now(),
                    result));
            return toModelResult(result);
        } catch (ProductSearchUnavailableException exception) {
            log.warn("product_tool_failed requestId={}, userId={}, exceptionType={}",
                    requestContext.requestId(), requestContext.identity().userId(),
                    exception.getClass().getSimpleName());
            return "商品查询服务暂时不可用，请稍后重试。";
        }
    }

    private AgentToolRequestContext requestContext(ToolContext context) {
        if (context == null) {
            throw new IllegalStateException("商品工具缺少请求上下文");
        }
        Object value = context.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext requestContext)) {
            throw new IllegalStateException("商品工具请求上下文不合法");
        }
        return requestContext;
    }

    private String toModelResult(ProductSearchResult result) {
        if (result.items().isEmpty()) {
            return "未查询到匹配商品。";
        }
        StringBuilder text = new StringBuilder("前端已展示本页")
                .append(result.items().size())
                .append("条商品卡片，共查询到")
                .append(result.total())
                .append("条SKU结果。请只用一到两句中文概括数量、关键结论或筛选建议；")
                .append("不要逐条复述商品，不要输出Markdown表格。")
                .append("以下本页数据仅用于回答用户的比较或判断问题：\n");
        for (ProductSearchItem item : result.items()) {
            text.append("- 商品=").append(safe(item.goodsName()))
                    .append("，SKU=").append(safe(item.skuCode()))
                    .append("，规格=").append(safe(item.goodsModel()))
                    .append("，价格(分)=").append(item.priceInFen())
                    .append("，库存=").append(item.availableStock())
                    .append("，状态=").append(safe(item.listingStatusText()))
                    .append('\n');
        }
        if (result.hasMore()) {
            text.append("还有更多结果，可继续查询下一页。");
        }
        return text.toString();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }
}
