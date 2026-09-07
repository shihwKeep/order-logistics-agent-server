package com.xjjk.agent.product.domain;

import java.util.List;

/**
 * 一次商品工具查询的结构化结果。
 */
public record ProductSearchResult(
        String keyword,
        int pageIndex,
        int pageSize,
        long total,
        boolean hasMore,
        List<ProductSearchItem> items) {

    public ProductSearchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
