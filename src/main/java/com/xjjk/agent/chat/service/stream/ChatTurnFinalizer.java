package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.observation.AgentTurnTelemetry;
import com.xjjk.agent.chat.service.turn.ChatTurnFinishService;
import com.xjjk.agent.chat.stream.ChatEventPublisher;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.chat.stream.ChatStreamError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 本轮应用级收尾：协调取消、数据库结果、SSE 终态和最终指标。
 * 数据库事务仍由 ChatTurnFinishService 管理，不因拆分改变事务边界。
 * 不重试落库、不绕过请求资格校验、不在发送失败后重新覆盖业务结果。
 */
@Slf4j
@Service
public class ChatTurnFinalizer {

    private final ChatTurnFinishService finishService;
    private final AgentTurnTelemetry turnTelemetry;

    @Autowired
    public ChatTurnFinalizer(
            ChatTurnFinishService finishService,
            AgentTurnTelemetry turnTelemetry
    ) {
        this.finishService = finishService;
        this.turnTelemetry = turnTelemetry;
    }

    /** 兼容不启动 Spring 容器的既有单元测试。 */
    ChatTurnFinalizer(ChatTurnFinishService finishService) {
        this(finishService, null);
    }

    // 包级入口，仅供本模块的工作线程执行器调用。
    void finish(
            ChatTurnExecution execution,
            ChatStreamControl control,
            ChatEventPublisher session
    ) {
        // beginFinalization 原子地冻结首个停止原因，并阻止后续回调反复改变最终状态。
        MessageStatus stopReason = control.beginFinalization();
        // 取消不能打断收尾事务；完成后恢复中断标记。
        boolean restoreInterrupt = Thread.interrupted();
        if (stopReason != null) {
            execution.status = stopReason;
            execution.error = ChatStreamError.forStatus(stopReason);
        }

        String metricStatus = execution.status.name();
        try {
            // 顺序不能调换：先将最终状态和已生成正文保存到 MySQL，
            // 再向前端发送 done/error，避免客户端收到成功但数据库没有对应结果。
            PersistenceOutcome outcome = persist(execution);
            if (outcome.metricStatus() != null) {
                metricStatus = outcome.metricStatus();
            }
            emitTerminal(execution, session, stopReason, outcome.saved());
            if (!execution.outputBroken) {
                session.complete();
            }
        } catch (IOException | RuntimeException exception) {
            // 终止事件发送失败不再次落库：MySQL 结果已经确定，重复收尾可能覆盖正确状态。
            // 同时保留比 OUTPUT_ERROR 更严重的持久化失败或请求失效指标。
            if (!"PERSISTENCE_FAILED".equals(metricStatus)
                    && !"STALE_REQUEST".equals(metricStatus)) {
                metricStatus = MessageStatus.OUTPUT_ERROR.name();
            }
            log.warn("Chat terminal output failed: requestId={}, exceptionType={}",
                    execution.requestId, exception.getClass().getSimpleName());
        } finally {
            try {
                // 指标放在最外层 finally，确保准备失败、取消、落库失败等路径都能被观测。
                if (execution.metrics != null) {
                    log.info("chat_call_metrics={}",
                            execution.metrics.snapshot(metricStatus, execution.finishReason));
                }
                if (turnTelemetry != null) {
                    turnTelemetry.completeCurrent(
                            metricStatus,
                            "UNKNOWN",
                            execution.turn == null ? null : execution.turn.conversationId());
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private PersistenceOutcome persist(ChatTurnExecution execution) {
        if (execution.turn == null) {
            return new PersistenceOutcome(false, null);
        }
        try {
            // finish 在一个短事务中保存回答、推进稳定历史游标并释放会话占用；
            // 事务提交成功后，其内部发布的历史变更事件会异步预热新版本 Redis 快照。
            boolean saved = finishService.finish(
                    execution.turn,
                    execution.status,
                    execution.content.toString(),
                    execution.finishReason,
                    execution.error == null ? null : execution.error.code(),
                    execution.resultSnapshot()
            );
            if (saved) {
                return new PersistenceOutcome(true, null);
            }
            execution.error = new ChatStreamError(
                    "CHAT_REQUEST_INACTIVE", "本次请求已结束或不再有效，请查看会话记录");
            return new PersistenceOutcome(false, "STALE_REQUEST");
        } catch (RuntimeException exception) {
            execution.error = new ChatStreamError(
                    "CHAT_SAVE_FAILED", "回答保存失败，请稍后查看会话记录确认");
            log.error("Chat finalization failed: requestId={}, exceptionType={}",
                    execution.requestId, exception.getClass().getSimpleName());
            return new PersistenceOutcome(false, "PERSISTENCE_FAILED");
        }
    }

    private void emitTerminal(
            ChatTurnExecution execution,
            ChatEventPublisher session,
            MessageStatus stopReason,
            boolean saved
    ) throws IOException {
        // 直连 SSE 已断开时没有继续写出的意义；Redis 回放发布器与连接解耦，
        // 必须追加取消/超时终态，否则 status 与 resume 会长期误判为 RUNNING。
        if ((stopReason != null && !session.detached()) || execution.outputBroken) {
            return;
        }
        if (execution.turn != null && saved && execution.status == MessageStatus.SUCCESS) {
            // 唯一的正常结束出口：完整回答已经成功落库。
            session.done(execution.turn.assistantMessageId());
        } else {
            ChatStreamError error = execution.error != null
                    ? execution.error : ChatStreamError.forStatus(MessageStatus.FAILED);
            session.error(error, execution.requestId);
        }
    }

    /** 收尾持久化结果，不向外部暴露，也不包含正文。 */
    private record PersistenceOutcome(boolean saved, String metricStatus) {
    }
}
