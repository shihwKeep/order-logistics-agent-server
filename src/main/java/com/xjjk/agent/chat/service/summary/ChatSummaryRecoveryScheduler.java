package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 回收进程中断留下的租约，并补建缺失或落后的持久化任务目标。 */
@Slf4j
@Component
public class ChatSummaryRecoveryScheduler {

    private static final int RECOVERY_BATCH_SIZE = 10;

    private final AgentSummaryTaskMapper taskMapper;
    private final AgentConversationMapper conversationMapper;
    private final ChatSummaryTaskScheduler taskScheduler;
    private final ChatSummaryProperties properties;
    private final Clock clock;

    @Autowired
    public ChatSummaryRecoveryScheduler(
            AgentSummaryTaskMapper taskMapper,
            AgentConversationMapper conversationMapper,
            ChatSummaryTaskScheduler taskScheduler,
            ChatSummaryProperties properties
    ) {
        this(taskMapper, conversationMapper, taskScheduler,
                properties, Clock.systemUTC());
    }

    ChatSummaryRecoveryScheduler(
            AgentSummaryTaskMapper taskMapper,
            AgentConversationMapper conversationMapper,
            ChatSummaryTaskScheduler taskScheduler,
            ChatSummaryProperties properties,
            Clock clock
    ) {
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /** 恢复频率独立于普通轮询，并由 Nacos 统一提供。 */
    @Scheduled(fixedDelayString =
            "${agent.chat.summary.worker.recovery-interval}")
    public void recover() {
        if (!properties.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC
        );
        // 第一类恢复：处理进程退出、线程中断等原因遗留的过期 PROCESSING 租约，
        // 未耗尽重试次数的任务回到 RETRY，耗尽后进入 DEAD。
        int recovered = taskMapper.recoverExpiredLeases(
                now,
                properties.retry().maxAttempts(),
                RECOVERY_BATCH_SIZE
        );
        if (recovered > 0) {
            log.info("chat_summary_recovery recoveredLeases={}", recovered);
        }

        // 第二类恢复：扫描已经产生稳定历史、但摘要任务缺失或目标水位落后的会话，
        // 通过幂等 upsert 补建任务，避免提交事务后的极端故障造成永久漏摘要。
        List<AgentConversationEntity> conversations =
                conversationMapper.selectSummaryRepairCandidates(
                        RECOVERY_BATCH_SIZE
                );
        for (AgentConversationEntity conversation : conversations) {
            try {
                taskScheduler.repairStableHistory(
                        conversation.getTenantId(),
                        conversation.getUserId(),
                        conversation.getConversationId(),
                        conversation.getMemoryVersion(),
                        conversation.getMemoryUntilSequence()
                );
            } catch (RuntimeException error) {
                // 单个会话补偿失败不能阻断本批其他会话；日志不输出异常消息和正文。
                log.warn("chat_summary_recovery conversationId={}, "
                                + "result=FAILED, errorType={}",
                        conversation.getConversationId(),
                        error.getClass().getSimpleName());
            }
        }
    }
}
