package com.xjjk.agent.product.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryToolsTest {

    @Test
    void publishesFullUiResultButReturnsCompactModelResultWithoutImageUrl() {
        ProductSearchResult result = new ProductSearchResult(
                "鱼油", 1, 10, 1, false,
                List.of(new ProductSearchItem(
                        12L, "SPU-12", "SKU-12-A", "深海鱼油", "60粒/瓶",
                        19900L, 8L, "ON_SHELF", "已上架",
                        "https://img.example/12.jpg", false)));
        AtomicReference<ToolUiResult> published = new AtomicReference<>();
        ProductQueryTools tools = new ProductQueryTools(query -> result);
        AgentToolRequestContext requestContext = new AgentToolRequestContext(
                "request-1",
                new AgentIdentity(10567L, "10567", "测试坐席", 1L, 1L),
                published::set,
                new ToolCallGuard(3));

        String modelResult = tools.searchProducts(
                " 鱼油 ", 1,
                new ToolContext(java.util.Map.of(
                        AgentToolRequestContext.CONTEXT_KEY, requestContext)));

        assertThat(published.get().toolName()).isEqualTo("search_products");
        assertThat(published.get().kind()).isEqualTo("product-list");
        assertThat(published.get().schemaVersion()).isEqualTo(1);
        assertThat(published.get().queriedAt()).isNotNull();
        assertThat(published.get().data()).isSameAs(result);
        assertThat(modelResult).contains(
                "前端已展示",
                "不要逐条复述",
                "不要输出Markdown表格",
                "SKU-12-A",
                "深海鱼油",
                "库存=8");
        assertThat(modelResult).doesNotContain("https://img.example/12.jpg");
    }

    @Test
    void normalizesCanonicalArgumentsAndPublishesDuplicateCallOnlyOnce() {
        ProductSearchResult result = new ProductSearchResult(
                "鱼油", 1, 10, 0, false, List.of());
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        ProductQueryTools tools = new ProductQueryTools(query -> {
            searches.incrementAndGet();
            return result;
        });
        AgentToolRequestContext requestContext = new AgentToolRequestContext(
                "request-1",
                new AgentIdentity(10567L, "10567", "测试坐席", 1L, 1L),
                ignored -> publications.incrementAndGet(),
                new ToolCallGuard(3));
        ToolContext toolContext = new ToolContext(java.util.Map.of(
                AgentToolRequestContext.CONTEXT_KEY, requestContext));

        String first = tools.searchProducts(" 鱼油 ", null, toolContext);
        String duplicate = tools.searchProducts("鱼油", 1, toolContext);

        assertThat(first).isEqualTo("未查询到匹配商品。");
        assertThat(duplicate).isEqualTo(first);
        assertThat(searches).hasValue(1);
        assertThat(publications).hasValue(1);
    }
}
