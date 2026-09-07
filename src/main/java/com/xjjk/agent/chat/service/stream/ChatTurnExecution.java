package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.observation.ChatCallMetricsCollector;
import com.xjjk.agent.chat.stream.ChatStreamError;

/**
 * 本轮执行的可变状态，只允许工作线程使用，不是 Spring Bean。
 * 包可见字段仅由同包的执行器和收尾器访问，避免向其他模块暴露正文。
 * 不生成 toString，防止日志意外输出回答内容。
 */
final class ChatTurnExecution {

    /** 开始事务完成后赋值；准备失败时为空。 */
    ChatTurnContext turn;
    /** 正式请求的调用指标；准备失败时为空。 */
    ChatCallMetricsCollector metrics;
    /** 准备前为临时标识，准备成功后切换为正式请求 ID。 */
    String requestId;
    /** 模型已经返回的部分正文，先积累再向客户端发送。 */
    final StringBuilder content = new StringBuilder();
    String finishReason;
    MessageStatus status = MessageStatus.FAILED;
    ChatStreamError error = ChatStreamError.preparationFailed();
    boolean outputBroken;

    ChatTurnExecution(String fallbackRequestId) {
        requestId = fallbackRequestId;
    }

    void prepared(ChatTurnContext preparedTurn) {
        turn = preparedTurn;
        requestId = turn.requestId();
        metrics = new ChatCallMetricsCollector(
                requestId, turn.conversationId(), turn.promptVersion());
    }
}
