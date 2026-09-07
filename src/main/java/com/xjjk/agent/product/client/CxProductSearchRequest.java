package com.xjjk.agent.product.client;

/** cxservice 商品查询请求。 */
public record CxProductSearchRequest(String keyword, int pageIndex, int pageSize) {
}
