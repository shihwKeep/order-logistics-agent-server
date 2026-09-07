package com.xjjk.agent.product.domain;

/**
 * 前端可直接展示的一条 SKU 商品结果。
 *
 * @param goodsId 商品主键
 * @param spuCode SPU 编码
 * @param skuCode SKU 编码
 * @param goodsName 商品名称
 * @param goodsModel 商品规格
 * @param priceInFen 价格，单位为分
 * @param availableStock 门店可售库存
 * @param listingStatus 稳定的上下架状态码
 * @param listingStatusText 上下架状态中文说明
 * @param coverImageUrl 封面图地址，仅发送给前端，不发送给模型
 * @param detailAvailable 详情页是否已经可用
 */
public record ProductSearchItem(
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
