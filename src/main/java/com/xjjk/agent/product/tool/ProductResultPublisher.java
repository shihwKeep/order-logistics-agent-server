package com.xjjk.agent.product.tool;

import com.xjjk.agent.product.domain.ProductSearchResult;

/** 把完整商品查询结果发布到当前 SSE 请求。 */
@FunctionalInterface
public interface ProductResultPublisher {
    void publish(ProductSearchResult result);
}
