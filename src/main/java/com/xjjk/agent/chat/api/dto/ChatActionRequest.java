package com.xjjk.agent.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前端结构化卡片触发的确定性聊天动作。
 *
 * @param type 白名单动作类型
 * @param orderCode 卡片携带的完整订单号
 */
public record ChatActionRequest(
        @NotBlank(message = "动作类型不能为空")
        @Size(max = 64, message = "动作类型不能超过64个字符")
        String type,

        @NotBlank(message = "订单号不能为空")
        @Size(max = 64, message = "订单号不能超过64个字符")
        String orderCode
) {
}
