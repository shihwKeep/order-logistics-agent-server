package com.xjjk.agent.product.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchResult;
import java.util.List;
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
        AtomicReference<ProductSearchResult> published = new AtomicReference<>();
        ProductQueryTools tools = new ProductQueryTools(query -> result);
        ProductToolRequestContext requestContext = new ProductToolRequestContext(
                "request-1",
                new AgentIdentity(10567L, "10567", "测试坐席", 1L, 1L),
                published::set);

        String modelResult = tools.searchProducts(
                " 鱼油 ", 1,
                new ToolContext(java.util.Map.of(
                        ProductToolRequestContext.CONTEXT_KEY, requestContext)));

        assertThat(published.get()).isSameAs(result);
        assertThat(modelResult).contains(
                "前端已展示",
                "不要逐条复述",
                "不要输出Markdown表格",
                "SKU-12-A",
                "深海鱼油",
                "库存=8");
        assertThat(modelResult).doesNotContain("https://img.example/12.jpg");
    }
}
