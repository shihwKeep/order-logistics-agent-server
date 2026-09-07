package com.xjjk.agent.product.client;

import java.util.List;

/** cxservice 商品分页数据。 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CxProductSearchData(
        List<CxProductSearchItem> items,
        long pageIndex,
        long pageSize,
        long total,
        boolean hasMore) {
}
