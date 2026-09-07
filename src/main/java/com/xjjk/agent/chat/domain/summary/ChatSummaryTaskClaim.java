package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

/**
 * Worker 领取任务后在事务外使用的不可变租约快照。
 *
 * previousSummaryVersion 和 previousCoveredUntilSequence 是领取瞬间的摘要 CAS 基线。
 *
 * @param taskDatabaseId 摘要任务数据库主键
 * @param taskId 对外日志使用的任务标识
 * @param tenantId 任务所属租户
 * @param userId 任务所属坐席用户
 * @param conversationId 任务所属会话
 * @param capturedRequestedMemoryVersion 领取时捕获的待评估稳定历史版本
 * @param capturedRequestedUntilSequence 捕获版本对应的稳定消息边界
 * @param lastEvaluatedMemoryVersion 领取前最后完成判断的稳定历史版本
 * @param forceGeneration 是否因上下文空档等原因要求强制生成
 * @param forceReason 强制生成原因；非强制任务为空
 * @param retryCount 领取时看到的历史重试次数，仅用于观测而不作为失败更新依据
 * @param leaseToken 本次领取生成的唯一租约令牌
 * @param lockedBy 持有租约的应用实例标识
 * @param previousSummaryVersion 领取时已有摘要版本，作为提交 CAS 基线
 * @param previousCoveredUntilSequence 领取时摘要已连续覆盖到的消息序号
 */
public record ChatSummaryTaskClaim(
        long taskDatabaseId,
        String taskId,
        long tenantId,
        long userId,
        String conversationId,
        long capturedRequestedMemoryVersion,
        long capturedRequestedUntilSequence,
        long lastEvaluatedMemoryVersion,
        boolean forceGeneration,
        ChatSummaryTriggerReason forceReason,
        int retryCount,
        String leaseToken,
        String lockedBy,
        long previousSummaryVersion,
        long previousCoveredUntilSequence
) {

    public ChatSummaryTaskClaim {
        if (taskDatabaseId <= 0
                || !StringUtils.hasText(taskId)
                || tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)
                || capturedRequestedMemoryVersion < 1
                || capturedRequestedUntilSequence < 1
                || lastEvaluatedMemoryVersion < 0
                || lastEvaluatedMemoryVersion
                > capturedRequestedMemoryVersion
                || retryCount < 0
                || !StringUtils.hasText(leaseToken)
                || !StringUtils.hasText(lockedBy)
                || previousSummaryVersion < 0
                || previousCoveredUntilSequence < 0) {
            throw new IllegalArgumentException("摘要任务租约快照不合法");
        }
        if (forceGeneration && forceReason == null
                || !forceGeneration && forceReason != null) {
            throw new IllegalArgumentException("摘要强制标记与原因不一致");
        }
    }

    @Override
    public String toString() {
        return "ChatSummaryTaskClaim[taskDatabaseId=" + taskDatabaseId
                + ", taskId=" + taskId
                + ", tenantId=" + tenantId
                + ", userId=" + userId
                + ", conversationId=" + conversationId
                + ", capturedRequestedMemoryVersion="
                + capturedRequestedMemoryVersion
                + ", capturedRequestedUntilSequence="
                + capturedRequestedUntilSequence
                + ", lastEvaluatedMemoryVersion="
                + lastEvaluatedMemoryVersion
                + ", forceGeneration=" + forceGeneration
                + ", forceReason=" + forceReason
                + ", retryCount=" + retryCount
                + ", previousSummaryVersion=" + previousSummaryVersion
                + ", previousCoveredUntilSequence="
                + previousCoveredUntilSequence + ']';
    }
}
