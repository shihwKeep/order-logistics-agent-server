package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.observation.ChatCallMetricsCollector;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.stream.ChatStreamError;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    /** 工具已经生成并通过序列化、大小校验的结构化结果。 */
    private final List<PendingMessageResult> results = new ArrayList<>();
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

    /** 返回下一条结果序号；调用方必须在同一个 execution 锁内完成登记和发布。 */
    int nextResultSequence() {
        return results.size() + 1;
    }

    /**
     * 将待持久化结果按连续序号加入本轮执行态。
     * 结果一旦登记，即使模型随后失败或客户端断开，收尾事务仍会保存它。
     */
    void addResult(PendingMessageResult result) {
        PendingMessageResult value = Objects.requireNonNull(result, "结构化结果不能为空");
        if (value.resultSequence() != nextResultSequence()) {
            throw new IllegalArgumentException("结构化结果序号必须连续递增");
        }
        results.add(value);
    }

    /** 收尾只能读取不可变快照，避免事务执行时集合继续变化。 */
    List<PendingMessageResult> resultSnapshot() {
        return List.copyOf(results);
    }
}
