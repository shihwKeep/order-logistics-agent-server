package com.xjjk.agent.chat.domain.memory;

import java.util.List;
import java.util.Objects;

/**
 * 历史上下文的 Token 预算筛选结果。
 *
 * 仅供内部使用，不直接返回前端。
 * 保留原快照，便于追踪身份范围和上游读取裁剪信息。
 *
 * @param source 原始历史快照
 * @param selectedTurns 选中的完整轮次，按时间从旧到新排列
 * @param selectedSummary 受控渲染后的摘要上下文；未选中时为 null
 * @param summaryEstimatedTokens 摘要消息相对固定输入增加的估算 Token
 * @param selectedSummaryVersion 已选摘要版本；无摘要时为 0
 * @param selectedSummaryCoveredUntilSequence 已选摘要覆盖边界；无摘要时为 0
 * @param selectedBusinessReference 已选的低权限业务引用；未选中时为 null
 * @param businessReferenceEstimatedTokens 业务引用相对既有输入增加的估算 Token
 * @param businessReferenceBudgetTruncated 是否因预算不足放弃业务引用
 * @param selectedHistoryFromSequence 选中原文起始序号；无原文时为 0
 * @param selectedHistoryUntilSequence 选中原文结束序号；无原文时为 0
 * @param hasContextGap 摘要边界与选中原文之间是否存在未覆盖空档
 * @param gapFromSequence 空档起始序号；无空档时为 0
 * @param gapUntilSequence 空档结束序号；无空档时为 0
 * @param budget 当前请求的预算
 * @param estimatedInputTokens 包含选中历史的完整输入估算值
 * @param tokenBudgetTruncated 是否因 Token 预算而舍弃了候选轮次
 * @param strategyVersion 本次使用的估算策略版本
 */
public record ChatContextSelection(
        ChatHistorySnapshot source,
        List<ChatHistoryTurn> selectedTurns,
        String selectedSummary,
        long summaryEstimatedTokens,
        long selectedSummaryVersion,
        long selectedSummaryCoveredUntilSequence,
        String selectedBusinessReference,
        long businessReferenceEstimatedTokens,
        boolean businessReferenceBudgetTruncated,
        long selectedHistoryFromSequence,
        long selectedHistoryUntilSequence,
        boolean hasContextGap,
        long gapFromSequence,
        long gapUntilSequence,
        ChatContextBudget budget,
        long estimatedInputTokens,
        boolean tokenBudgetTruncated,
        String strategyVersion,
        String effectiveSystemPrompt,
        String selectedUserMemoryContext,
        long userMemoryEstimatedTokens,
        boolean userMemoryBudgetTruncated
) {

    public ChatContextSelection {
        Objects.requireNonNull(source, "原始快照不能为空");
        Objects.requireNonNull(selectedTurns, "选中轮次不能为空");
        Objects.requireNonNull(budget, "上下文预算不能为空");

        selectedTurns = List.copyOf(selectedTurns);

        if (strategyVersion == null || strategyVersion.isBlank()) {
            throw new IllegalArgumentException("估算策略版本不能为空");
        }
        if (effectiveSystemPrompt == null || effectiveSystemPrompt.isBlank()) {
            throw new IllegalArgumentException("实际系统提示词不能为空");
        }

        if (estimatedInputTokens <= 0
                || estimatedInputTokens > budget.usableInputTokens()) {
            throw new IllegalArgumentException("最终输入估算值不在预算内");
        }

        if ((selectedSummary == null) != (summaryEstimatedTokens == 0)
                || summaryEstimatedTokens < 0) {
            throw new IllegalArgumentException("摘要选择结果与估算值不一致");
        }
        if (selectedSummary == null) {
            if (selectedSummaryVersion != 0
                    || selectedSummaryCoveredUntilSequence != 0) {
                throw new IllegalArgumentException("无摘要时不能携带摘要元数据");
            }
        } else if (selectedSummaryVersion < 1
                || selectedSummaryCoveredUntilSequence < 1
                || selectedSummaryCoveredUntilSequence
                > source.memoryUntilSequence()) {
            throw new IllegalArgumentException("已选摘要版本或覆盖边界不合法");
        }

        if ((selectedBusinessReference == null)
                != (businessReferenceEstimatedTokens == 0)
                || businessReferenceEstimatedTokens < 0) {
            throw new IllegalArgumentException("业务引用选择结果与估算值不一致");
        }
        if (selectedBusinessReference != null
                && selectedBusinessReference.isBlank()) {
            throw new IllegalArgumentException("业务引用不能为空白文本");
        }

        if ((selectedUserMemoryContext == null) != (userMemoryEstimatedTokens == 0)
                || userMemoryEstimatedTokens < 0) {
            throw new IllegalArgumentException("用户记忆选择结果与估算值不一致");
        }
        if (selectedUserMemoryContext != null && selectedUserMemoryContext.isBlank()) {
            throw new IllegalArgumentException("用户记忆上下文不能为空白文本");
        }
        if (selectedUserMemoryContext != null && userMemoryBudgetTruncated) {
            throw new IllegalArgumentException("已选用户记忆不能同时标记预算舍弃");
        }
        if (selectedBusinessReference != null
                && businessReferenceBudgetTruncated) {
            throw new IllegalArgumentException("已选业务引用不能同时标记预算舍弃");
        }

        int sourceSize = source.turns().size();
        int selectedSize = selectedTurns.size();

        if (selectedSize > sourceSize) {
            throw new IllegalArgumentException("选中轮次超过候选范围");
        }

        // 摘要覆盖过滤只会移除前缀，因此选中原文仍必须是源快照的连续后缀。
        List<ChatHistoryTurn> expected = source.turns().subList(
                sourceSize - selectedSize, sourceSize);
        if (!selectedTurns.equals(expected)) {
            throw new IllegalArgumentException(
                    "选中历史必须是最近的一段完整候选轮次"
            );
        }

        if (selectedSize == 0) {
            if (selectedHistoryFromSequence != 0
                    || selectedHistoryUntilSequence != 0) {
                throw new IllegalArgumentException("无原文时不能携带原文边界");
            }
        } else if (selectedHistoryFromSequence
                != selectedTurns.get(0).userSequence()
                || selectedHistoryUntilSequence
                != selectedTurns.get(selectedSize - 1).assistantSequence()) {
            throw new IllegalArgumentException("选中原文边界与轮次不一致");
        }

        if (selectedSummary != null) {
            for (ChatHistoryTurn turn : selectedTurns) {
                if (turn.userSequence()
                        <= selectedSummaryCoveredUntilSequence
                        || turn.assistantSequence()
                        <= selectedSummaryCoveredUntilSequence) {
                    throw new IllegalArgumentException("选中原文与摘要覆盖范围重叠");
                }
            }

            long expectedGapFrom = Math.addExact(
                    selectedSummaryCoveredUntilSequence, 1L);
            long nextVisible = selectedTurns.isEmpty()
                    ? Math.addExact(source.memoryUntilSequence(), 1L)
                    : selectedHistoryFromSequence;
            boolean expectedGap = nextVisible > expectedGapFrom;
            long expectedGapUntil = expectedGap
                    ? nextVisible - 1L : 0L;
            if (hasContextGap != expectedGap
                    || gapFromSequence != (expectedGap
                    ? expectedGapFrom : 0L)
                    || gapUntilSequence != expectedGapUntil) {
                throw new IllegalArgumentException("上下文空档边界与摘要、原文不一致");
            }
        }

        if (hasContextGap) {
            if (selectedSummary == null || gapFromSequence < 1
                    || gapUntilSequence < gapFromSequence) {
                throw new IllegalArgumentException("上下文空档元数据不合法");
            }
        } else if (gapFromSequence != 0 || gapUntilSequence != 0) {
            throw new IllegalArgumentException("无空档时不能携带空档边界");
        }
    }

    /** 业务引用是否进入本次模型上下文。 */
    public boolean hasBusinessReference() {
        return selectedBusinessReference != null;
    }

    public boolean hasUserMemoryContext() {
        return selectedUserMemoryContext != null;
    }

    /** 兼容加入用户记忆字段之前的完整构造调用。 */
    public ChatContextSelection(
            ChatHistorySnapshot source,
            List<ChatHistoryTurn> selectedTurns,
            String selectedSummary,
            long summaryEstimatedTokens,
            long selectedSummaryVersion,
            long selectedSummaryCoveredUntilSequence,
            String selectedBusinessReference,
            long businessReferenceEstimatedTokens,
            boolean businessReferenceBudgetTruncated,
            long selectedHistoryFromSequence,
            long selectedHistoryUntilSequence,
            boolean hasContextGap,
            long gapFromSequence,
            long gapUntilSequence,
            ChatContextBudget budget,
            long estimatedInputTokens,
            boolean tokenBudgetTruncated,
            String strategyVersion
    ) {
        this(source, selectedTurns, selectedSummary, summaryEstimatedTokens,
                selectedSummaryVersion, selectedSummaryCoveredUntilSequence,
                selectedBusinessReference, businessReferenceEstimatedTokens,
                businessReferenceBudgetTruncated, selectedHistoryFromSequence,
                selectedHistoryUntilSequence, hasContextGap, gapFromSequence,
                gapUntilSequence, budget, estimatedInputTokens, tokenBudgetTruncated,
                strategyVersion, "legacy-system-prompt", null, 0L, false);
    }

    /** 兼容不包含业务引用的既有构造调用。 */
    public ChatContextSelection(
            ChatHistorySnapshot source,
            List<ChatHistoryTurn> selectedTurns,
            String selectedSummary,
            long summaryEstimatedTokens,
            long selectedSummaryVersion,
            long selectedSummaryCoveredUntilSequence,
            long selectedHistoryFromSequence,
            long selectedHistoryUntilSequence,
            boolean hasContextGap,
            long gapFromSequence,
            long gapUntilSequence,
            ChatContextBudget budget,
            long estimatedInputTokens,
            boolean tokenBudgetTruncated,
            String strategyVersion
    ) {
        this(source, selectedTurns, selectedSummary, summaryEstimatedTokens,
                selectedSummaryVersion, selectedSummaryCoveredUntilSequence,
                null, 0L, false,
                selectedHistoryFromSequence, selectedHistoryUntilSequence,
                hasContextGap, gapFromSequence, gapUntilSequence,
                budget, estimatedInputTokens, tokenBudgetTruncated,
                strategyVersion, "legacy-system-prompt", null, 0L, false);
    }

    /**
     * 只输出元信息，不输出历史正文。
     */
    @Override
    public String toString() {
        return "ChatContextSelection[conversationId="
                + source.conversationId()
                + ", memoryVersion=" + source.memoryVersion()
                + ", memoryUntilSequence="
                + source.memoryUntilSequence()
                + ", beforeSequence=" + source.beforeSequence()
                + ", candidateTurns=" + source.turns().size()
                + ", selectedTurns=" + selectedTurns.size()
                + ", hasSelectedSummary=" + (selectedSummary != null)
                + ", summaryEstimatedTokens=" + summaryEstimatedTokens
                + ", selectedSummaryVersion=" + selectedSummaryVersion
                + ", selectedSummaryCoveredUntilSequence="
                + selectedSummaryCoveredUntilSequence
                + ", hasBusinessReference=" + hasBusinessReference()
                + ", businessReferenceEstimatedTokens="
                + businessReferenceEstimatedTokens
                + ", businessReferenceBudgetTruncated="
                + businessReferenceBudgetTruncated
                + ", hasUserMemoryContext=" + hasUserMemoryContext()
                + ", userMemoryEstimatedTokens=" + userMemoryEstimatedTokens
                + ", userMemoryBudgetTruncated=" + userMemoryBudgetTruncated
                + ", selectedHistoryFromSequence="
                + selectedHistoryFromSequence
                + ", selectedHistoryUntilSequence="
                + selectedHistoryUntilSequence
                + ", hasContextGap=" + hasContextGap
                + ", gapFromSequence=" + gapFromSequence
                + ", gapUntilSequence=" + gapUntilSequence
                + ", estimatedInputTokens=" + estimatedInputTokens
                + ", usableInputTokens=" + budget.usableInputTokens()
                + ", tokenBudgetTruncated=" + tokenBudgetTruncated
                + ", hasEarlierMessages=" + source.hasEarlierMessages()
                + ", readBudgetTruncated=" + source.readBudgetTruncated()
                + ", strategyVersion=" + strategyVersion
                + "]";
    }
}
