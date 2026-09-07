package com.xjjk.agent.chat.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @Size(max = 64, message = "会话ID不能超过64个字符")
        String conversationId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "消息内容不能超过2000个字符")
        String message,

        @Valid
        ChatActionRequest action
) {
}
