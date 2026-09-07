package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.domain.memory.ChatContextBudget;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.service.model.AiModelRuntimeSettings;
import com.xjjk.agent.chat.service.summary.ChatSummaryContextRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * 根据 Token 预算选择最近的完整历史轮次。
 *
 * 不读取数据库、不修改原始历史、不调用模型。
 * 每次对完整候选输入重新估算，不直接累加各轮 Token 数。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatContextSelector {

    private final QwenChatTokenEstimator tokenEstimator;

    private final AiModelRuntimeSettings runtimeSettings;

    private final ChatSummaryContextRenderer summaryRenderer;

    private final ChatSummaryProperties summaryProperties;

    /**
     * 选择本次请求能够容纳的历史。
     *
     * @param systemPrompt 实际调用使用的系统提示词
     * @param currentMessage 当前用户问题
     * @param source 已完成权限检查的历史快照
     * @return 不超过当前预算的筛选结果
     */
    public ChatContextSelection select(
            String systemPrompt,
            String currentMessage,
            ChatHistorySnapshot source
    ) {
        ChatHistoryCursor cursor = new ChatHistoryCursor(
                source.tenantId(), source.userId(), source.conversationId(),
                source.memoryVersion(), source.memoryUntilSequence(),
                source.beforeSequence()
        );
        return select(
                systemPrompt,
                currentMessage,
                source,
                ChatSummarySnapshot.empty(cursor)
        );
    }

    /** 在统一预算中组合受控摘要与最近完整原始轮次。 */
    public ChatContextSelection select(
            String systemPrompt,
            String currentMessage,
            ChatHistorySnapshot source,
            ChatSummarySnapshot summary
    ) {
        Objects.requireNonNull(source, "历史快照不能为空");
        Objects.requireNonNull(summary, "摘要快照不能为空");
        checkInterrupted();

        // 先确认系统提示词和当前问题本身能够放入预算。
        long fixedEstimatedTokens = tokenEstimator.estimate(
                systemPrompt,
                List.of(),
                currentMessage
        );

        ChatContextBudget budget =
                runtimeSettings.calculateBudget(fixedEstimatedTokens);

        validateSameScope(source, summary);

        String selectedSummary = null;
        long summaryEstimatedTokens = 0L;
        long selectedSummaryVersion = 0L;
        long summaryBoundary = 0L;
        List<ChatHistoryTurn> candidates = source.turns();
        int reservedStart = candidates.size();

        if (summary.present()) {
            List<ChatHistoryTurn> uncoveredCandidates =
                    source.turns().stream()
                            .filter(turn -> turn.userSequence()
                                    > summary.coveredUntilSequence()
                                    && turn.assistantSequence()
                                    > summary.coveredUntilSequence())
                            .toList();
            int rawTailStart = reserveRecentRawTail(
                    systemPrompt, currentMessage, uncoveredCandidates,
                    fixedEstimatedTokens, budget
            );
            List<ChatHistoryTurn> reservedRaw = uncoveredCandidates.subList(
                    rawTailStart, uncoveredCandidates.size());
            String rendered = renderSummaryFailOpen(source, summary);

            if (rendered != null) {
                long summaryOnlyTokens = tokenEstimator.estimate(
                        systemPrompt, rendered, List.of(), currentMessage
                );
                long summaryWithReservedRaw = reservedRaw.isEmpty()
                        ? summaryOnlyTokens
                        : tokenEstimator.estimate(
                                systemPrompt, rendered,
                                reservedRaw, currentMessage);

                // raw-tail 预算内最新完整轮次已先保留；摘要不能挤掉它们。
                if (summaryOnlyTokens <= budget.usableInputTokens()
                        && summaryWithReservedRaw
                        <= budget.usableInputTokens()) {
                    selectedSummary = rendered;
                    summaryEstimatedTokens = Math.subtractExact(
                            summaryOnlyTokens, fixedEstimatedTokens);
                    selectedSummaryVersion = summary.summaryVersion();
                    summaryBoundary = summary.coveredUntilSequence();
                    candidates = uncoveredCandidates;
                    reservedStart = rawTailStart;
                }
            }
        }

        // 初始不包含任何历史。
        int selectedStart = selectedSummary == null
                ? candidates.size() : reservedStart;
        long selectedEstimatedTokens = Math.addExact(
                fixedEstimatedTokens, summaryEstimatedTokens);
        if (selectedSummary != null && selectedStart < candidates.size()) {
            selectedEstimatedTokens = tokenEstimator.estimate(
                    systemPrompt, selectedSummary,
                    candidates.subList(selectedStart, candidates.size()),
                    currentMessage
            );
        }
        boolean tokenBudgetTruncated = false;

        // 摘要已选中时，raw-tail 后缀不可被挤掉，只从它之前继续回填更老原文。
        int nextCandidate = selectedSummary == null
                ? candidates.size() - 1 : selectedStart - 1;
        for (int index = nextCandidate; index >= 0; index--) {
            checkInterrupted();

            // 原列表升序，后缀仍然保持从旧到新的消息顺序。
            List<ChatHistoryTurn> trialTurns =
                    candidates.subList(index, candidates.size());

            long trialEstimatedTokens = selectedSummary == null
                    ? tokenEstimator.estimate(
                            systemPrompt, trialTurns, currentMessage)
                    : tokenEstimator.estimate(
                            systemPrompt, selectedSummary,
                            trialTurns, currentMessage);

            checkInterrupted();

            if (trialEstimatedTokens > budget.usableInputTokens()) {
                tokenBudgetTruncated = true;
                break;
            }

            // 只有这次候选输入放得下，才更新已接受的结果。
            selectedStart = index;
            selectedEstimatedTokens = trialEstimatedTokens;
        }

        checkInterrupted();

        List<ChatHistoryTurn> selectedTurns =
                candidates.subList(selectedStart, candidates.size());
        long selectedFrom = selectedTurns.isEmpty()
                ? 0L : selectedTurns.get(0).userSequence();
        long selectedUntil = selectedTurns.isEmpty()
                ? 0L : selectedTurns.get(selectedTurns.size() - 1)
                .assistantSequence();
        long gapFrom = 0L;
        long gapUntil = 0L;
        if (selectedSummary != null) {
            long firstUncovered = Math.addExact(summaryBoundary, 1L);
            long nextVisible = selectedTurns.isEmpty()
                    ? Math.addExact(source.memoryUntilSequence(), 1L)
                    : selectedFrom;
            if (nextVisible > firstUncovered) {
                gapFrom = firstUncovered;
                gapUntil = nextVisible - 1L;
            }
        }

        return new ChatContextSelection(
                source,
                selectedTurns,
                selectedSummary,
                summaryEstimatedTokens,
                selectedSummaryVersion,
                summaryBoundary,
                selectedFrom,
                selectedUntil,
                gapFrom > 0,
                gapFrom,
                gapUntil,
                budget,
                selectedEstimatedTokens,
                tokenBudgetTruncated,
                tokenEstimator.strategyVersion()
        );
    }

    private int reserveRecentRawTail(
            String systemPrompt,
            String currentMessage,
            List<ChatHistoryTurn> candidates,
            long fixedEstimatedTokens,
            ChatContextBudget budget
    ) {
        long rawTailLimit = fixedEstimatedTokens
                > Long.MAX_VALUE - summaryProperties.rawTailMaxTokens()
                ? Long.MAX_VALUE
                : fixedEstimatedTokens
                + summaryProperties.rawTailMaxTokens();
        rawTailLimit = Math.min(rawTailLimit, budget.usableInputTokens());

        int selectedStart = candidates.size();
        for (int index = candidates.size() - 1; index >= 0; index--) {
            checkInterrupted();
            long estimate = tokenEstimator.estimate(
                    systemPrompt,
                    candidates.subList(index, candidates.size()),
                    currentMessage
            );
            if (estimate > budget.usableInputTokens()) {
                break;
            }
            if (estimate > rawTailLimit) {
                // 单个最新轮次超过 raw-tail 配额但仍放得下时，最新完整轮次优先。
                if (selectedStart == candidates.size()) {
                    selectedStart = index;
                }
                break;
            }
            selectedStart = index;
        }
        return selectedStart;
    }

    private String renderSummaryFailOpen(
            ChatHistorySnapshot source,
            ChatSummarySnapshot summary
    ) {
        try {
            return summaryRenderer.render(summary.content());
        } catch (CancellationException | SecurityException exception) {
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // 可解析但不可安全渲染的摘要仅在本次请求降级，日志不包含正文。
            log.warn("summary_context_render_failed conversationId={}, "
                            + "summaryVersion={}, coveredUntilSequence={}, "
                            + "exceptionType={}",
                    source.conversationId(), summary.summaryVersion(),
                    summary.coveredUntilSequence(),
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private static void validateSameScope(
            ChatHistorySnapshot source,
            ChatSummarySnapshot summary
    ) {
        if (source.tenantId() != summary.tenantId()
                || source.userId() != summary.userId()
                || !source.conversationId().equals(summary.conversationId())) {
            throw new SecurityException("摘要与历史快照归属不一致");
        }
        if (source.memoryUntilSequence()
                != summary.stableMemoryUntilSequence()) {
            throw new IllegalArgumentException("摘要与历史快照稳定边界不一致");
        }
    }

    /**
     * 响应工作线程的取消信号，不清除中断标记。
     * 检查发生在编码之间，不能强制中断正在执行的原生编码。
     */
    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("上下文筛选已取消");
        }
    }
}
