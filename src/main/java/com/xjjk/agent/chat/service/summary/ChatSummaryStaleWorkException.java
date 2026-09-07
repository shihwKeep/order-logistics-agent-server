package com.xjjk.agent.chat.service.summary;

/** 租约或摘要 CAS 基线已经过期，当前 Worker 必须停止提交。 */
public class ChatSummaryStaleWorkException extends RuntimeException {

    public ChatSummaryStaleWorkException(String safeMessage) {
        super(safeMessage);
    }
}
