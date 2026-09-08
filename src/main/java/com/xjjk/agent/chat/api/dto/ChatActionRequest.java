package com.xjjk.agent.chat.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 前端结构化卡片触发的确定性聊天动作。
 *
 * @param type 白名单动作类型
 * @param orderCode 订单卡片携带的完整订单号，仅物流动作使用
 * @param customerCode 客户卡片携带的完整客户编号，仅客户订单动作使用
 */
public record ChatActionRequest(
        @NotBlank(message = "动作类型不能为空")
        @Size(max = 64, message = "动作类型不能超过64个字符")
        String type,

        @Size(max = 64, message = "订单号不能超过64个字符")
        String orderCode,

        @Size(max = 128, message = "客户编号不能超过128个字符")
        String customerCode
) {
    /** 保持旧物流动作调用方的二参数构造方式。 */
    public ChatActionRequest(String type, String orderCode) {
        this(type, orderCode, null);
    }
}
