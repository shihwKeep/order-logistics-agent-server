package com.xjjk.agent.chat.stream;

import com.xjjk.agent.chat.domain.MessageStatus;

/** 安全的流式错误信息，不携带供应商异常或敏感正文。 */
public record ChatStreamError(String code, String message) {

    public static ChatStreamError preparationFailed() {
        return new ChatStreamError("CHAT_PREPARATION_FAILED", "聊天准备失败，请稍后重试");
    }

    public static ChatStreamError modelStreamRetryExhausted() {
        return new ChatStreamError(
                "MODEL_STREAM_RETRY_EXHAUSTED",
                "模型服务连接暂时不稳定，自动重试后仍未恢复，请稍后再试");
    }

    /** 将内部结果状态转换为安全的对外错误信息。 */
    public static ChatStreamError forStatus(MessageStatus status) {
        return switch (status) {
            case SUCCESS -> null;

            case OUTPUT_LIMIT -> new ChatStreamError(
                    "MODEL_OUTPUT_LIMIT",
                    "回答达到输出长度上限，内容可能不完整"
            );

            case EMPTY_RESPONSE -> new ChatStreamError(
                    "MODEL_EMPTY_RESPONSE",
                    "模型未返回有效文本，请稍后重试"
            );

            case INCOMPLETE -> new ChatStreamError(
                    "MODEL_RESPONSE_INCOMPLETE",
                    "模型回答未正常结束，内容可能不完整"
            );

            case TIMEOUT -> new ChatStreamError(
                    "CHAT_TIMEOUT",
                    "本次请求超时，回答可能不完整"
            );

            case CANCELLED -> new ChatStreamError(
                    "CHAT_CANCELLED",
                    "本次请求已取消"
            );

            case OUTPUT_ERROR -> new ChatStreamError(
                    "CHAT_OUTPUT_ERROR",
                    "响应传输失败，回答可能不完整"
            );

            case INTERRUPTED -> new ChatStreamError(
                    "CHAT_REQUEST_INTERRUPTED",
                    "本次请求异常中断"
            );

            default -> new ChatStreamError(
                    "MODEL_STREAM_FAILED",
                    "模型调用失败，请稍后重试"
            );
        };
    }

}
