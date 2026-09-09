package com.xjjk.agent.product.service;

import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;

/**
 * 商品主数据查询端口，由 cxservice 适配器实现。
 */
@FunctionalInterface
public interface ProductSearchGateway {
    /** 使用已经规范化的分页条件查询当前 Agent 可见的商品。 */
    ProductSearchResult search(ProductSearchQuery query);
}
