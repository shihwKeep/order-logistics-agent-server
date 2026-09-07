package com.xjjk.agent.chat.domain.summary;

import java.util.Objects;

/** 摘要生成阶段可安全记录和重试分类的异常，不携带会话正文或模型原始输出。 */
public class ChatSummaryGenerationException extends RuntimeException {

    private final Code code;

    public ChatSummaryGenerationException(Code code, String safeMessage) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "摘要错误码不能为空");
    }

    public ChatSummaryGenerationException(
            Code code,
            String safeMessage,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.code = Objects.requireNonNull(code, "摘要错误码不能为空");
    }

    public Code code() {
        return code;
    }

    /** 低基数错误类型，可直接用于任务状态和指标标签。 */
    public enum Code {
        MODEL_TIMEOUT,
        MODEL_CALL_FAILED,
        MODEL_PROTOCOL_ERROR,
        INPUT_SERIALIZATION_FAILED,
        INVALID_JSON_RESPONSE,
        INVALID_SUMMARY_STRUCTURE
    }
}
