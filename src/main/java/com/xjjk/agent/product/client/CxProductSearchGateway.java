package com.xjjk.agent.product.client;

import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.product.service.ProductSearchUnavailableException;
import com.xjjk.agent.integration.observation.DownstreamCallMetrics;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 将 cxservice 商品接口适配成 Agent 内部领域结果。
 */
@Component
public class CxProductSearchGateway implements ProductSearchGateway {
    /** cxservice 内部接口约定的成功业务码。 */
    private static final int CX_SUCCESS_CODE = 1000;

    private final CxProductClient client;
    private final String internalToken;
    private DownstreamCallMetrics downstreamMetrics;

    public CxProductSearchGateway(
            CxProductClient client,
            @Value("${integration.cx.internal-token}") String internalToken) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("integration.cx.internal-token 不能为空");
        }
        this.client = client;
        this.internalToken = internalToken;
    }

    @Autowired
    void setDownstreamMetrics(DownstreamCallMetrics downstreamMetrics) {
        this.downstreamMetrics = downstreamMetrics;
    }

    @Override
    public ProductSearchResult search(ProductSearchQuery query) {
        if (downstreamMetrics == null) {
            return doSearch(query);
        }
        return downstreamMetrics.observe("product", "search", () -> doSearch(query));
    }

    private ProductSearchResult doSearch(ProductSearchQuery query) {
        CxProductResponse<CxProductSearchData> response;
        try {
            // 内部 Token 只在适配器边界注入，模型工具与前端均无法读取或覆盖。
            response = client.search(
                    internalToken,
                    new CxProductSearchRequest(
                            query.keyword(), query.pageIndex(), query.pageSize()));
            if (downstreamMetrics != null) {
                downstreamMetrics.attempt("product", "search", 1, null, false);
            }
        } catch (RuntimeException exception) {
            if (downstreamMetrics != null) {
                downstreamMetrics.attempt("product", "search", 1, exception, false);
            }
            throw new ProductSearchUnavailableException("商品服务调用失败", exception);
        }
        if (response == null || response.code() == null
                || response.code() != CX_SUCCESS_CODE || response.data() == null) {
            throw new ProductSearchUnavailableException("商品服务响应不合法");
        }

        // 下游业务失败和协议缺失统一转换为领域不可用异常，不向模型暴露原始响应。
        CxProductSearchData data = response.data();
        List<ProductSearchItem> items = data.items() == null
                ? List.of()
                : data.items().stream().map(this::toDomain).toList();
        return new ProductSearchResult(
                query.keyword(),
                Math.toIntExact(data.pageIndex()),
                Math.toIntExact(data.pageSize()),
                data.total(),
                data.hasMore(),
                items);
    }

    /** 把下游 DTO 映射为 Agent 内部稳定领域协议，隔离 cxservice 字段变化。 */
    private ProductSearchItem toDomain(CxProductSearchItem item) {
        return new ProductSearchItem(
                item.goodsId(), item.spuCode(), item.skuCode(),
                item.goodsName(), item.goodsModel(), item.priceInFen(),
                item.availableStock(), item.listingStatus(),
                item.listingStatusText(), item.coverImageUrl(),
                item.detailAvailable());
    }
}
