package com.xjjk.agent.product.client;

/** cxservice 返回的一条 SKU 商品数据。 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CxProductSearchItem(
        Long goodsId,
        String spuCode,
        String skuCode,
        String goodsName,
        String goodsModel,
        long priceInFen,
        long availableStock,
        String listingStatus,
        String listingStatusText,
        String coverImageUrl,
        boolean detailAvailable) {
}
