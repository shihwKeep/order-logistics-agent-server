package com.xjjk.agent.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前端结构化卡片触发的确定性聊天动作。
 *
 * @param type 白名单动作类型
 * @param orderCode 订单卡片携带的完整订单号，仅物流动作使用
 * @param customerCode 客户卡片携带的完整客户编号，仅客户订单动作使用
 * @param afterSaleCode 售后卡片携带的完整售后工单号，仅售后详情动作使用
 * @param productKeyword 商品卡片或确定性商品查询携带的 SKU、SPU 或条码
 * @param productPageIndex 确定性商品查询页码，未提供时默认为第1页
 */
public record ChatActionRequest(
        @NotBlank(message = "动作类型不能为空")
        @Size(max = 64, message = "动作类型不能超过64个字符")
        String type,

        @Size(max = 64, message = "订单号不能超过64个字符")
        String orderCode,

        @Size(max = 128, message = "客户编号不能超过128个字符")
        String customerCode,

        @Size(max = 64, message = "售后工单号不能超过64个字符")
        String afterSaleCode,

        @Size(max = 64, message = "商品标识不能超过64个字符")
        String productKeyword,

        @jakarta.validation.constraints.Min(value = 1, message = "商品页码必须从1开始")
        Integer productPageIndex
) {
    /** 保持旧调用方的五参数构造方式。 */
    public ChatActionRequest(
            String type,
            String orderCode,
            String customerCode,
            String afterSaleCode,
            String productKeyword) {
        this(type, orderCode, customerCode, afterSaleCode, productKeyword, null);
    }

    /** 保持旧调用方的四参数构造方式。 */
    public ChatActionRequest(
            String type,
            String orderCode,
            String customerCode,
            String afterSaleCode) {
        this(type, orderCode, customerCode, afterSaleCode, null, null);
    }

    /** 保持旧物流动作调用方的二参数构造方式。 */
    public ChatActionRequest(String type, String orderCode) {
        this(type, orderCode, null, null, null, null);
    }

    /** 保持客户订单动作调用方的三参数构造方式。 */
    public ChatActionRequest(String type, String orderCode, String customerCode) {
        this(type, orderCode, customerCode, null, null, null);
    }
}
