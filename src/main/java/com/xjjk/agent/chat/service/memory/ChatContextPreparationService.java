package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.config.AiPromptProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;

/**
 * 模型上下文准备入口：历史加载、预算筛选、估算观测。
 * 不开启外层事务，保留历史加载器自己的短事务边界。
 * 后续缓存或摘要应在上下文层接入，不侵入 HTTP 入口和模型流循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextPreparationService {

    private final ChatHistorySnapshotProvider snapshotProvider;
    private final ChatContextSelector contextSelector;
    private final AiPromptProperties promptProperties;

    public ChatContextSelection prepare(
            ChatTurnContext turn,
            String message,
            ChatStreamControl control
    ) {
        checkStopped(control);
        ChatHistorySnapshot snapshot = loadSnapshot(turn);
        log.info("chat_history_snapshot requestId={}, snapshot={}", turn.requestId(), snapshot);
        checkStopped(control);

        long startedAt = System.nanoTime();
        ChatContextSelection selection =
                contextSelector.select(promptProperties.system(), message, snapshot);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        checkStopped(control);

        // 表示选中结果将交给模型调用路径，不代表远端已经接收。
        log.info("chat_context_selection requestId={}, appliedToModel=true, "
                        + "selectionDurationMs={}, selection={}",
                turn.requestId(), durationMs, selection);
        log.info("chat_token_estimate requestId={}, strategy={}, promptVersion={}, "
                        + "includedTurns={}, estimatedInputTokens={}",
                turn.requestId(), selection.strategyVersion(), promptProperties.version(),
                selection.selectedTurns().size(), selection.estimatedInputTokens());
        return selection;
    }

    private ChatHistorySnapshot loadSnapshot(ChatTurnContext turn) {
        try {
            return snapshotProvider.load(turn);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("chat_history_load_failed requestId={}, exceptionType={}",
                    turn.requestId(), exception.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.CHAT_HISTORY_LOAD_FAILED);
        }
    }

    private void checkStopped(ChatStreamControl control) {
        if (control.isStopRequested()) {
            throw new CancellationException("上下文准备已取消");
        }
    }
}
