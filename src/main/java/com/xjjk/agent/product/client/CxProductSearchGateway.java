package com.xjjk.agent.product.client;

import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.product.service.ProductSearchUnavailableException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 将 cxservice 商品接口适配成 Agent 内部领域结果。
 */
@Component
public class CxProductSearchGateway implements ProductSearchGateway {
    private static final int CX_SUCCESS_CODE = 1000;

    private final CxProductClient client;
    private final String internalToken;

    public CxProductSearchGateway(
            CxProductClient client,
            @Value("${integration.cx.internal-token}") String internalToken) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("integration.cx.internal-token 不能为空");
        }
        this.client = client;
        this.internalToken = internalToken;
    }

    @Override
    public ProductSearchResult search(ProductSearchQuery query) {
        CxProductResponse<CxProductSearchData> response;
        try {
            response = client.search(
                    internalToken,
                    new CxProductSearchRequest(
                            query.keyword(), query.pageIndex(), query.pageSize()));
        } catch (RuntimeException exception) {
            throw new ProductSearchUnavailableException("商品服务调用失败", exception);
        }
        if (response == null || response.code() == null
                || response.code() != CX_SUCCESS_CODE || response.data() == null) {
            throw new ProductSearchUnavailableException("商品服务响应不合法");
        }

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

    private ProductSearchItem toDomain(CxProductSearchItem item) {
        return new ProductSearchItem(
                item.goodsId(), item.spuCode(), item.skuCode(),
                item.goodsName(), item.goodsModel(), item.priceInFen(),
                item.availableStock(), item.listingStatus(),
                item.listingStatusText(), item.coverImageUrl(),
                item.detailAvailable());
    }
}
