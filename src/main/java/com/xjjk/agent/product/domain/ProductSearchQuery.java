package com.xjjk.agent.product.domain;

/**
 * 商品检索参数。模型只提供业务关键词和分页，不允许控制门店或租户。
 */
public record ProductSearchQuery(String keyword, int pageIndex, int pageSize) {
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    public static ProductSearchQuery of(String keyword, Integer pageIndex, Integer pageSize) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            throw new IllegalArgumentException("商品关键词不能为空");
        }
        return new ProductSearchQuery(
                normalizedKeyword,
                pageIndex == null ? 1 : Math.max(1, pageIndex),
                pageSize == null || pageSize <= 0
                        ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE));
    }
}
