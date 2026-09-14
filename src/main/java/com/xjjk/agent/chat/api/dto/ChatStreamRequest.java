package com.xjjk.agent.chat.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @Size(max = 64, message = "会话ID不能超过64个字符")
        String conversationId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "消息内容不能超过2000个字符")
        String message,

        @Valid
        ChatActionRequest action,

        @NotBlank(message = "请求ID不能为空")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                message = "请求ID格式不合法"
        )
        String clientRequestId
) {

    /** 兼容服务内部逐步迁移；HTTP 入口仍通过字段校验强制要求请求 ID。 */
    public ChatStreamRequest(
            String conversationId,
            String message,
            ChatActionRequest action
    ) {
        this(conversationId, message, action, null);
    }
}
