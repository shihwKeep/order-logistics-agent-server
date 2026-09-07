package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 长期摘要任务登记服务。
 *
 * 每轮消息仍在原事务内只登记最新稳定游标，不同步调用摘要模型。
 * 数据库唯一键负责把同一会话的多次登记合并为一条持久化任务。
 */
@Service
@RequiredArgsConstructor
public class ChatSummaryTaskScheduler {

    private final AgentSummaryTaskMapper taskMapper;

    /**
     * 在问答收尾或恢复事务内登记新的稳定历史版本。
     * MANDATORY 防止调用方误把会话游标和摘要任务拆成两个独立提交。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestStableHistory(
            long tenantId,
            long userId,
            String conversationId,
            long memoryVersion,
            long memoryUntilSequence
    ) {
        request(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                false,
                null
        );
    }

    /**
     * 上下文读取发现摘要与近期原文之间出现空档时，独立短事务请求强制压缩。
     * 当前聊天请求不等待该任务，登记失败由调用方按降级策略处理。
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void requestContextPressure(
            long tenantId,
            long userId,
            String conversationId,
            long memoryVersion,
            long memoryUntilSequence
    ) {
        request(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                true,
                ChatSummaryTriggerReason.RAW_CONTEXT_PRESSURE.name()
        );
    }

    /** 宕机补偿使用独立短事务，幂等合并缺失或落后的摘要任务目标。 */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void repairStableHistory(
            long tenantId,
            long userId,
            String conversationId,
            long memoryVersion,
            long memoryUntilSequence
    ) {
        request(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                false,
                null
        );
    }

    private void request(
            long tenantId,
            long userId,
            String conversationId,
            long memoryVersion,
            long memoryUntilSequence,
            boolean forceGeneration,
            String forceReason
    ) {
        validateTarget(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence
        );

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        int rows = taskMapper.upsertRequestedTarget(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                forceGeneration,
                forceReason,
                UUID.randomUUID().toString(),
                now
        );

        // 0 行表示会话归属或稳定游标已改变，不能登记一个来源不可信的目标。
        if (rows < 1) {
            throw new IllegalStateException("会话归属或稳定历史游标不匹配");
        }
    }

    private static void validateTarget(
            long tenantId,
            long userId,
            String conversationId,
            long memoryVersion,
            long memoryUntilSequence
    ) {
        if (tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)
                || memoryVersion < 1
                || memoryUntilSequence < 2) {
            throw new IllegalArgumentException("摘要任务稳定历史目标不合法");
        }
    }
}
