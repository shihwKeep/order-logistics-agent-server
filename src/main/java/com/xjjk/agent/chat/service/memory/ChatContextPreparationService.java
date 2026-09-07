package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.config.AiPromptProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.reference.ChatBusinessContextRenderer;
import com.xjjk.agent.chat.reference.RecentOrderReferenceProvider;
import com.xjjk.agent.chat.service.summary.ChatSummaryProvider;
import com.xjjk.agent.chat.service.summary.ChatSummaryTaskScheduler;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;

/**
 * 模型上下文准备入口：历史加载、预算筛选、估算观测。
 * 不开启外层事务，保留历史加载器自己的短事务边界。
 * 后续缓存或摘要应在上下文层接入，不侵入 HTTP 入口和模型流循环。
 */
@Slf4j
@Service
public class ChatContextPreparationService {

    private final ChatHistorySnapshotProvider snapshotProvider;
    private final ChatSummaryProvider summaryProvider;
    private final ChatContextSelector contextSelector;
    private final ChatSummaryTaskScheduler summaryTaskScheduler;
    private final AiPromptProperties promptProperties;
    private final RecentOrderReferenceProvider orderReferenceProvider;
    private final ChatBusinessContextRenderer businessContextRenderer;

    /** 生产构造器：由 Spring 注入完整的短期记忆、摘要和会话内业务引用能力。 */
    @Autowired
    public ChatContextPreparationService(
            ChatHistorySnapshotProvider snapshotProvider,
            ChatSummaryProvider summaryProvider,
            ChatContextSelector contextSelector,
            ChatSummaryTaskScheduler summaryTaskScheduler,
            AiPromptProperties promptProperties,
            RecentOrderReferenceProvider orderReferenceProvider,
            ChatBusinessContextRenderer businessContextRenderer
    ) {
        this.snapshotProvider = snapshotProvider;
        this.summaryProvider = summaryProvider;
        this.contextSelector = contextSelector;
        this.summaryTaskScheduler = summaryTaskScheduler;
        this.promptProperties = promptProperties;
        this.orderReferenceProvider = orderReferenceProvider;
        this.businessContextRenderer = businessContextRenderer;
    }

    /**
     * 兼容既有单元测试及不启用业务引用的纯记忆装配；生产环境使用完整构造器。
     */
    public ChatContextPreparationService(
            ChatHistorySnapshotProvider snapshotProvider,
            ChatSummaryProvider summaryProvider,
            ChatContextSelector contextSelector,
            ChatSummaryTaskScheduler summaryTaskScheduler,
            AiPromptProperties promptProperties
    ) {
        this(snapshotProvider, summaryProvider, contextSelector,
                summaryTaskScheduler, promptProperties, null, null);
    }

    public ChatContextSelection prepare(
            ChatTurnContext turn,
            String message,
            ChatStreamControl control
    ) {
        // 第一步：读取当前会话的稳定短期历史快照；缓存未命中或不可用时由 Provider 回源 MySQL。
        checkStopped(control);
        ChatHistorySnapshot snapshot = loadSnapshot(turn);
        log.info("chat_history_snapshot requestId={}, snapshot={}", turn.requestId(), snapshot);
        checkStopped(control);

        ChatHistoryCursor cursor = new ChatHistoryCursor(
                snapshot.tenantId(), snapshot.userId(),
                snapshot.conversationId(), snapshot.memoryVersion(),
                snapshot.memoryUntilSequence(), snapshot.beforeSequence()
        );
        // 第二步：使用同一租户、用户、会话和稳定边界读取已提交摘要，未生成摘要时返回空快照。
        ChatSummarySnapshot summary = summaryProvider.load(cursor);
        checkStopped(control);

        // 第三步：只读取当前用户、当前会话、当前消息之前最新的一份结构化结果。
        // 只有结果能够唯一确定订单时才生成低权限引用；它只能帮助理解“这个订单”，
        // 不能作为订单状态、金额或物流信息的事实来源。
        String businessContext = loadBusinessContextBestEffort(
                turn, snapshot.beforeSequence());
        checkStopped(control);

        // 第四步：在统一 Token 预算内先保护最近原始轮次，再拼接低权限摘要和更早可容纳的原文。
        // Selector 同时计算摘要覆盖边界与原文起点，显式标记二者之间是否存在上下文空档。
        // 业务引用的优先级最低：只有在不挤占已选摘要和原始历史时才会进入模型上下文。
        long startedAt = System.nanoTime();
        ChatContextSelection selection = businessContext == null
                ? contextSelector.select(
                        promptProperties.system(), message, snapshot, summary)
                : contextSelector.select(
                        promptProperties.system(), message, snapshot, summary,
                        businessContext);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        checkStopped(control);

        // 第五步：出现空档时只登记后台强制摘要信号；当前请求继续使用已经安全选中的上下文。
        requestContextPressureBestEffort(selection);

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

    private String loadBusinessContextBestEffort(
            ChatTurnContext turn,
            long beforeSequence
    ) {
        if (orderReferenceProvider == null || businessContextRenderer == null) {
            return null;
        }
        try {
            return orderReferenceProvider.findUniqueBefore(turn, beforeSequence)
                    .map(businessContextRenderer::render)
                    .orElse(null);
        } catch (SecurityException exception) {
            // 身份或会话归属异常必须 fail-closed，不能降级为普通缓存未命中。
            throw exception;
        } catch (RuntimeException exception) {
            // 会话内业务引用是辅助上下文，读取失败不能阻断基础对话。
            log.warn("chat_business_reference_load_failed requestId={}, "
                            + "exceptionType={}",
                    turn.requestId(), exception.getClass().getSimpleName());
            return null;
        }
    }

    private void requestContextPressureBestEffort(
            ChatContextSelection selection
    ) {
        if (!selection.hasContextGap()) {
            return;
        }
        ChatHistorySnapshot source = selection.source();
        try {
            // 不比较已评估版本；同版本空档也必须把 force_generation 置为 true。
            summaryTaskScheduler.requestContextPressure(
                    source.tenantId(), source.userId(),
                    source.conversationId(), source.memoryVersion(),
                    source.memoryUntilSequence()
            );
        } catch (SecurityException exception) {
            // 归属不变量被破坏时必须 fail-closed，不能伪装成后台任务暂时失败。
            throw exception;
        } catch (RuntimeException exception) {
            // 压力摘要是后台自愈信号，失败不能阻断当前聊天请求。
            log.warn("summary_context_pressure_failed conversationId={}, "
                            + "memoryVersion={}, memoryUntilSequence={}, "
                            + "exceptionType={}",
                    source.conversationId(), source.memoryVersion(),
                    source.memoryUntilSequence(),
                    exception.getClass().getSimpleName());
        }
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
