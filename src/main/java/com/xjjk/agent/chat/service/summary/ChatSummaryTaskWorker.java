package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryGenerationException;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTrigger;
import com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 单个长期摘要任务的事务外编排器。
 *
 * 数据库锁只存在于领取和提交服务的短事务中；历史读取、触发判断和模型调用都不持锁。
 */
@Slf4j
@Service
public class ChatSummaryTaskWorker {

    private final ObjectMapper objectMapper;
    private final AgentConversationSummaryMapper summaryMapper;
    private final ChatSummaryCandidateLoader candidateLoader;
    private final ChatSummaryTriggerPolicy triggerPolicy;
    private final ChatSummaryGenerator generator;
    private final ChatSummaryCommitService commitService;
    private final ChatSummaryProperties properties;

    public ChatSummaryTaskWorker(
            ObjectMapper objectMapper,
            AgentConversationSummaryMapper summaryMapper,
            ChatSummaryCandidateLoader candidateLoader,
            ChatSummaryTriggerPolicy triggerPolicy,
            ChatSummaryGenerator generator,
            ChatSummaryCommitService commitService,
            ChatSummaryProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.summaryMapper = summaryMapper;
        this.candidateLoader = candidateLoader;
        this.triggerPolicy = triggerPolicy;
        this.generator = generator;
        this.commitService = commitService;
        this.properties = properties;
    }

    /** 执行一次租约快照；过期 Worker 的结果会在提交事务中被拒绝。 */
    public void process(ChatSummaryTaskClaim claim) {
        Objects.requireNonNull(claim, "摘要任务租约不能为空");
        try {
            // 第一步：按领取任务时记录的摘要版本和覆盖边界加载旧摘要。
            // 旧摘要已经变化说明其他 Worker 先完成了提交，本租约不能继续工作。
            ChatSummarySnapshot previous = loadSummarySnapshot(claim);

            // 第二步：只读取领取时捕获的稳定历史，并从旧摘要边界之后连续向前取完整轮次。
            // 候选加载器会依次执行消息数、字节数和 Token 三层预算控制。
            ChatSummaryCandidateBatch batch = candidateLoader.load(
                    claim.tenantId(), claim.userId(), claim.conversationId(),
                    claim.previousCoveredUntilSequence(),
                    claim.capturedRequestedMemoryVersion(),
                    claim.capturedRequestedUntilSequence()
            );
            if (batch.turns().isEmpty()
                    && batch.hasMoreEligibleMessages()) {
                // 第一轮本身已超过硬预算时不能截断正文，也不能把捕获版本误报为已评估。
                // 这是数据与配置不兼容，需要进入 DEAD 并告警后人工调整预算。
                commitService.markDead(
                        claim,
                        "SUMMARY_CANDIDATE_TOO_LARGE"
                );
                log.error("chat_summary_task taskId={}, conversationId={}, "
                                + "result=DEAD, errorCode={}",
                        claim.taskId(), claim.conversationId(),
                        "SUMMARY_CANDIDATE_TOO_LARGE");
                return;
            }

            // 第三步：结合候选规模、强制生成标记和剩余积压，判断本次是否调用摘要模型。
            ChatSummaryTrigger trigger = triggerPolicy.evaluate(
                    batch,
                    claim.forceGeneration(),
                    batch.hasMoreEligibleMessages()
            );

            // 影子模式只验证读取和触发策略，不调用模型、不写摘要正文。
            if (properties.shadowMode() || !trigger.shouldGenerate()) {
                commitService.completeWithoutGeneration(claim);
                log.info("chat_summary_task taskId={}, conversationId={}, "
                                + "capturedVersion={}, selectedUntil={}, "
                                + "triggerReason={}, result={}",
                        claim.taskId(), claim.conversationId(),
                        claim.capturedRequestedMemoryVersion(),
                        batch.selectedUntilSequence(), trigger.reason(),
                        properties.shadowMode() ? "SHADOW" : "SKIPPED");
                return;
            }

            // 第四步：模型调用发生在数据库事务之外，避免等待远端响应时长期持有行锁。
            ChatSummaryDraft draft = generator.generate(previous, batch);

            // 第五步：重新校验租约和旧摘要 CAS 基线后，在同一短事务中提交摘要与任务水位。
            commitService.commitSummary(claim, batch, draft);
            log.info("chat_summary_task taskId={}, conversationId={}, "
                            + "capturedVersion={}, selectedUntil={}, "
                            + "triggerReason={}, result=COMMITTED",
                    claim.taskId(), claim.conversationId(),
                    claim.capturedRequestedMemoryVersion(),
                    batch.selectedUntilSequence(), trigger.reason());
        } catch (ChatSummaryStaleWorkException stale) {
            // 新租约或新摘要已胜出，不重试旧结果。
            log.info("chat_summary_task taskId={}, conversationId={}, "
                            + "result=STALE",
                    claim.taskId(), claim.conversationId());
        } catch (ChatSummaryGenerationException error) {
            fail(claim, error.code().name());
        } catch (RuntimeException error) {
            // 只记录异常类型，不记录可能携带正文的异常消息。
            log.warn("chat_summary_task taskId={}, conversationId={}, "
                            + "result=FAILED, errorType={}",
                    claim.taskId(), claim.conversationId(),
                    error.getClass().getSimpleName());
            fail(claim, "SUMMARY_PROCESSING_FAILED");
        }
    }

    private ChatSummarySnapshot loadSummarySnapshot(
            ChatSummaryTaskClaim claim
    ) {
        AgentConversationSummaryEntity entity = summaryMapper.selectOwned(
                claim.tenantId(), claim.userId(), claim.conversationId()
        );
        if (entity == null) {
            if (claim.previousSummaryVersion() != 0
                    || claim.previousCoveredUntilSequence() != 0) {
                throw new ChatSummaryStaleWorkException("摘要基线已经变化");
            }
            return new ChatSummarySnapshot(
                    claim.tenantId(), claim.userId(), claim.conversationId(),
                    0, 0, 0,
                    claim.capturedRequestedUntilSequence(),
                    null, null, null
            );
        }
        if (!Objects.equals(entity.getSummaryVersion(),
                claim.previousSummaryVersion())
                || !Objects.equals(entity.getCoveredUntilSequence(),
                claim.previousCoveredUntilSequence())) {
            throw new ChatSummaryStaleWorkException("摘要基线已经变化");
        }
        try {
            ChatSummaryContent content = objectMapper.readValue(
                    entity.getContentJson(), ChatSummaryContent.class
            );
            if (content.schemaVersion() != properties.schemaVersion()) {
                throw new IllegalStateException("已提交摘要结构版本不受支持");
            }
            return new ChatSummarySnapshot(
                    claim.tenantId(), claim.userId(), claim.conversationId(),
                    entity.getSummaryVersion(),
                    entity.getCoveredUntilSequence(),
                    entity.getSourceMemoryVersion(),
                    claim.capturedRequestedUntilSequence(),
                    content, entity.getPromptVersion(), entity.getModelName()
            );
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已提交摘要结构损坏", error);
        }
    }

    private void fail(ChatSummaryTaskClaim claim, String errorCode) {
        try {
            // 失败次数必须在持有任务行锁后读取，不能使用领取时的旧快照；
            // PROCESSING 期间的新目标可能已经把数据库重试次数清零。
            commitService.scheduleRetry(claim, errorCode);
        } catch (ChatSummaryStaleWorkException stale) {
            // 失败状态提交前租约已被回收，无需覆盖新 Worker 状态。
            log.info("chat_summary_task taskId={}, conversationId={}, "
                            + "result=STALE_ON_FAILURE",
                    claim.taskId(), claim.conversationId());
        }
    }
}
