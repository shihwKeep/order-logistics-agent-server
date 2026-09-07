package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 一次摘要任务读取出的连续候选批次。
 *
 * 批次只能覆盖完整轮次，并且从上一版摘要边界的下一条消息连续开始。
 *
 * @param tenantId 候选历史所属租户
 * @param userId 候选历史所属坐席用户
 * @param conversationId 候选历史所属会话
 * @param previousCoveredUntilSequence 上一版摘要已经连续覆盖到的消息序号
 * @param capturedMemoryVersion Worker 领取任务时捕获的稳定历史版本
 * @param capturedUntilSequence 捕获版本对应的稳定消息边界
 * @param turns 本批按时间升序排列的完整问答轮次
 * @param selectedFromSequence 本批第一条用户消息序号；空批次为 0
 * @param selectedUntilSequence 本批最后一条助手消息序号；空批次等于旧摘要边界
 * @param contentBytes 本批原始正文 UTF-8 总字节数
 * @param estimatedTokens 本批送入摘要生成输入的预估 Token 数
 * @param scanLimitReached 是否因单批最大消息数停止扫描
 * @param readBudgetTruncated 是否因正文读取字节预算停止选择
 * @param tokenBudgetTruncated 是否因摘要模型输入 Token 预算停止选择
 * @param hasMoreEligibleMessages 当前批次之后是否仍有已确认可摘要的连续历史
 */
public record ChatSummaryCandidateBatch(
        long tenantId,
        long userId,
        String conversationId,
        long previousCoveredUntilSequence,
        long capturedMemoryVersion,
        long capturedUntilSequence,
        List<ChatSummaryCandidateTurn> turns,
        long selectedFromSequence,
        long selectedUntilSequence,
        long contentBytes,
        long estimatedTokens,
        boolean scanLimitReached,
        boolean readBudgetTruncated,
        boolean tokenBudgetTruncated,
        boolean hasMoreEligibleMessages
) {

    public ChatSummaryCandidateBatch {
        if (tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)
                || previousCoveredUntilSequence < 0
                || capturedMemoryVersion < 1
                || capturedUntilSequence < previousCoveredUntilSequence) {
            throw new IllegalArgumentException("摘要候选批次游标不合法");
        }
        turns = List.copyOf(turns);
        if (turns.isEmpty()) {
            if (selectedFromSequence != 0
                    || selectedUntilSequence != previousCoveredUntilSequence
                    || contentBytes != 0 || estimatedTokens != 0) {
                throw new IllegalArgumentException("空摘要批次不能携带正文范围");
            }
        } else {
            long expectedFrom = Math.addExact(previousCoveredUntilSequence, 1L);
            if (selectedFromSequence != expectedFrom
                    || selectedUntilSequence
                    != turns.get(turns.size() - 1).assistantSequence()
                    || contentBytes <= 0 || estimatedTokens <= 0) {
                throw new IllegalArgumentException("摘要候选批次范围不连续");
            }
        }
    }

    /** 是否因任一读取上限而只取得部分可摘要历史。 */
    public boolean limitReached() {
        return scanLimitReached
                || readBudgetTruncated
                || tokenBudgetTruncated;
    }

    /** 日志只输出范围和预算，不输出候选正文。 */
    @Override
    public String toString() {
        return "ChatSummaryCandidateBatch[tenantId=" + tenantId
                + ", userId=" + userId
                + ", conversationId=" + conversationId
                + ", previousCoveredUntilSequence="
                + previousCoveredUntilSequence
                + ", capturedMemoryVersion=" + capturedMemoryVersion
                + ", capturedUntilSequence=" + capturedUntilSequence
                + ", turnCount=" + turns.size()
                + ", selectedFromSequence=" + selectedFromSequence
                + ", selectedUntilSequence=" + selectedUntilSequence
                + ", contentBytes=" + contentBytes
                + ", estimatedTokens=" + estimatedTokens
                + ", limitReached=" + limitReached()
                + ", hasMoreEligibleMessages=" + hasMoreEligibleMessages
                + ", content=<redacted>]";
    }
}
