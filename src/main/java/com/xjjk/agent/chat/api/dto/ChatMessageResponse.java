package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 历史消息响应。
 *
 * 不暴露数据库自增主键。
 * 非成功消息也返回，由前端根据状态展示异常或不完整提示。
 *
 * @param messageId 对外消息 ID
 * @param requestId 所属问答请求 ID
 * @param messageSequence 会话内消息序号
 * @param role 消息角色：USER 或 ASSISTANT
 * @param content 已保存的消息正文
 * @param status 消息处理状态
 * @param finishReason 模型结束原因，未知时为空
 * @param errorCode 业务错误码，正常消息为空
 * @param createdAt 创建时间，由转换逻辑转为上海时区
 * @param results 助手消息关联的结构化工具结果，按结果序号升序排列
 */
public record ChatMessageResponse(
        String messageId,
        String requestId,
        long messageSequence,
        String role,
        String content,
        String status,
        String finishReason,
        String errorCode,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        OffsetDateTime createdAt,
        List<ChatMessageResultResponse> results
) {
}
