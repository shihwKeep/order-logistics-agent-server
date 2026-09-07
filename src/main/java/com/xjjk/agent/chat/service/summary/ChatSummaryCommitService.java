package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity;
import com.xjjk.agent.chat.persistence.entity.AgentSummaryTaskEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 摘要任务领取、CAS 提交和失败状态流转的短事务边界。 */
@Service
public class ChatSummaryCommitService {

    private final ObjectMapper objectMapper;
    private final AgentConversationSummaryMapper summaryMapper;
    private final AgentSummaryTaskMapper taskMapper;
    private final ChatSummaryProperties properties;
    private final Clock clock;

    @Autowired
    public ChatSummaryCommitService(
            ObjectMapper objectMapper,
            AgentConversationSummaryMapper summaryMapper,
            AgentSummaryTaskMapper taskMapper,
            ChatSummaryProperties properties
    ) {
        this(objectMapper, summaryMapper, taskMapper, properties,
                Clock.systemUTC());
    }

    ChatSummaryCommitService(
            ObjectMapper objectMapper,
            AgentConversationSummaryMapper summaryMapper,
            AgentSummaryTaskMapper taskMapper,
            ChatSummaryProperties properties,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.summaryMapper = summaryMapper;
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** 多实例安全领取任务，事务结束后模型调用不会继续持有数据库锁。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public List<ChatSummaryTaskClaim> claimAvailable(int limit) {
        if (limit < 1 || limit > properties.worker().claimBatchSize()) {
            throw new IllegalArgumentException("摘要领取数量不合法");
        }
        LocalDateTime now = now();
        List<AgentSummaryTaskEntity> tasks =
                taskMapper.selectClaimableForUpdate(now, limit);
        List<ChatSummaryTaskClaim> claims = new ArrayList<>(tasks.size());
        for (AgentSummaryTaskEntity task : tasks) {
            // 每次领取生成唯一租约令牌，并把任务目标、旧摘要版本一起冻结为不可变快照。
            // 后续模型调用不持锁，提交时再凭该快照判断结果是否仍然有效。
            String leaseToken = UUID.randomUUID().toString();
            int updated = taskMapper.markClaimed(
                    task.getId(), leaseToken,
                    properties.worker().instanceId(),
                    now.plus(properties.worker().leaseDuration()), now
            );
            if (updated != 1) {
                throw new ChatSummaryStaleWorkException("摘要任务领取资格已变化");
            }
            AgentConversationSummaryEntity summary = summaryMapper.selectOwned(
                    task.getTenantId(), task.getUserId(),
                    task.getConversationId()
            );
            claims.add(toClaim(task, summary, leaseToken));
        }
        return List.copyOf(claims);
    }

    /** 提交模型摘要与任务水位，二者在同一事务中成功或回滚。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void commitSummary(
            ChatSummaryTaskClaim claim,
            ChatSummaryCandidateBatch batch,
            ChatSummaryDraft draft
    ) {
        Objects.requireNonNull(batch, "摘要候选批次不能为空");
        Objects.requireNonNull(draft, "摘要草稿不能为空");
        validateBatch(claim, batch);
        AgentSummaryTaskEntity leased = requireLease(claim);
        AgentConversationSummaryEntity current = summaryMapper.selectOwned(
                claim.tenantId(), claim.userId(), claim.conversationId()
        );
        // 租约有效只能证明任务仍归当前 Worker；还必须验证旧摘要没有被其他 Worker 推进。
        verifySummaryBaseline(claim, current);

        AgentConversationSummaryEntity next = buildSummary(
                claim, batch, draft, current
        );
        int summaryUpdated = current == null
                ? summaryMapper.insert(next)
                : summaryMapper.updateCas(
                        next,
                        claim.previousSummaryVersion(),
                        claim.previousCoveredUntilSequence()
                );
        if (summaryUpdated != 1) {
            throw new ChatSummaryStaleWorkException("摘要 CAS 基线已变化");
        }

        // 提交期间如果又登记了更新目标，或本批仍有积压，当前提交成功后必须重新进入 PENDING。
        String nextStatus = needsAnotherRun(leased, claim,
                batch.hasMoreEligibleMessages()) ? "PENDING" : "IDLE";
        completeLease(claim, nextStatus, null);
    }

    /** 完成一次不触发模型的有效判断，只推进评估版本，不改摘要覆盖边界。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void completeWithoutGeneration(ChatSummaryTaskClaim claim) {
        AgentSummaryTaskEntity leased = requireLease(claim);
        String nextStatus = needsAnotherRun(leased, claim, false)
                ? "PENDING" : "IDLE";
        completeLease(claim, nextStatus, null);
    }

    /** 按数据库中的当前次数退避；达到上限时原子进入 DEAD。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void scheduleRetry(ChatSummaryTaskClaim claim, String errorCode) {
        requireSafeErrorCode(errorCode);
        AgentSummaryTaskEntity leased = requireLease(claim);
        int retryCount = Math.addExact(leased.getRetryCount(), 1);
        LocalDateTime now = now();
        boolean exhausted = retryCount >= properties.retry().maxAttempts();
        LocalDateTime nextRunAt = exhausted
                ? now : nextRetryAt(now, retryCount);
        int updated = taskMapper.failLease(
                claim.taskDatabaseId(), claim.leaseToken(), claim.lockedBy(),
                exhausted ? "DEAD" : "RETRY", retryCount, nextRunAt,
                errorCode, now
        );
        requireSingleTaskUpdate(updated);
    }

    /** 达到最大尝试次数后进入 DEAD，等待人工排查或补偿任务重新激活。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void markDead(ChatSummaryTaskClaim claim, String errorCode) {
        requireSafeErrorCode(errorCode);
        AgentSummaryTaskEntity leased = requireLease(claim);
        LocalDateTime now = now();
        int updated = taskMapper.failLease(
                claim.taskDatabaseId(), claim.leaseToken(), claim.lockedBy(),
                "DEAD", Math.addExact(leased.getRetryCount(), 1),
                now, errorCode, now
        );
        requireSingleTaskUpdate(updated);
    }

    private AgentSummaryTaskEntity requireLease(ChatSummaryTaskClaim claim) {
        Objects.requireNonNull(claim, "摘要任务租约不能为空");
        AgentSummaryTaskEntity leased = taskMapper.selectLeaseForUpdate(
                claim.taskDatabaseId(), claim.leaseToken(), claim.lockedBy()
        );
        // 令牌尚未被恢复任务替换，也不代表租约仍有效；超过 locked_until 的旧 Worker
        // 必须拒绝提交，避免它抢在低频恢复调度器之前写入过期模型结果。
        if (leased == null || leased.getLockedUntil() == null
                || !leased.getLockedUntil().isAfter(now())) {
            throw new ChatSummaryStaleWorkException("摘要任务租约已失效");
        }
        return leased;
    }

    private void completeLease(
            ChatSummaryTaskClaim claim,
            String status,
            String errorCode
    ) {
        // 只把领取时捕获的目标推进为“已评估”；PROCESSING 期间新增的更高版本仍留给下一轮处理。
        // Mapper 同时校验租约令牌和实例标识，并负责清空本次租约字段。
        int updated = taskMapper.completeLease(
                claim.taskDatabaseId(), claim.leaseToken(), claim.lockedBy(),
                claim.capturedRequestedMemoryVersion(), status, errorCode, now()
        );
        requireSingleTaskUpdate(updated);
    }

    /** 把已校验草稿转换为下一版持久化实体；覆盖边界只能取本批最后一个完整助手序号。 */
    private AgentConversationSummaryEntity buildSummary(
            ChatSummaryTaskClaim claim,
            ChatSummaryCandidateBatch batch,
            ChatSummaryDraft draft,
            AgentConversationSummaryEntity current
    ) {
        AgentConversationSummaryEntity entity =
                new AgentConversationSummaryEntity();
        entity.setId(current == null ? null : current.getId());
        entity.setTenantId(claim.tenantId());
        entity.setUserId(claim.userId());
        entity.setConversationId(claim.conversationId());
        entity.setSummaryVersion(Math.addExact(
                claim.previousSummaryVersion(), 1L));
        entity.setCoveredUntilSequence(batch.selectedUntilSequence());
        entity.setSourceMemoryVersion(
                claim.capturedRequestedMemoryVersion());
        entity.setSchemaVersion(draft.content().schemaVersion());
        try {
            entity.setContentJson(objectMapper.writeValueAsString(
                    draft.content()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已校验摘要序列化失败", error);
        }
        entity.setPromptVersion(draft.promptVersion());
        entity.setModelName(draft.modelName());
        entity.setInputTokens(draft.inputTokens());
        entity.setOutputTokens(draft.outputTokens());
        LocalDateTime now = now();
        entity.setCreatedAt(current == null ? now : current.getCreatedAt());
        entity.setUpdatedAt(now);
        return entity;
    }

    /** 校验领取时冻结的旧摘要 CAS 基线，拒绝覆盖其他 Worker 已提交的新版本。 */
    private static void verifySummaryBaseline(
            ChatSummaryTaskClaim claim,
            AgentConversationSummaryEntity current
    ) {
        if (current == null) {
            if (claim.previousSummaryVersion() != 0
                    || claim.previousCoveredUntilSequence() != 0) {
                throw new ChatSummaryStaleWorkException("摘要基线记录已消失");
            }
            return;
        }
        if (!Objects.equals(current.getSummaryVersion(),
                claim.previousSummaryVersion())
                || !Objects.equals(current.getCoveredUntilSequence(),
                claim.previousCoveredUntilSequence())) {
            throw new ChatSummaryStaleWorkException("摘要基线版本已变化");
        }
    }

    private static void validateBatch(
            ChatSummaryTaskClaim claim,
            ChatSummaryCandidateBatch batch
    ) {
        if (claim.tenantId() != batch.tenantId()
                || claim.userId() != batch.userId()
                || !claim.conversationId().equals(batch.conversationId())
                || claim.capturedRequestedMemoryVersion()
                != batch.capturedMemoryVersion()
                || claim.capturedRequestedUntilSequence()
                != batch.capturedUntilSequence()
                || claim.previousCoveredUntilSequence()
                != batch.previousCoveredUntilSequence()
                || batch.turns().isEmpty()) {
            throw new IllegalArgumentException("摘要提交批次与租约游标不一致");
        }
    }

    /** 判断当前提交后是否仍需执行：候选有积压、收到强制请求或出现了更新的稳定历史。 */
    private static boolean needsAnotherRun(
            AgentSummaryTaskEntity leased,
            ChatSummaryTaskClaim claim,
            boolean backlog
    ) {
        return backlog
                || Boolean.TRUE.equals(leased.getForceGeneration())
                || leased.getRequestedMemoryVersion()
                > claim.capturedRequestedMemoryVersion();
    }

    /** 将数据库锁内状态冻结为事务外可用的租约快照，正文和密钥不会进入该对象。 */
    private ChatSummaryTaskClaim toClaim(
            AgentSummaryTaskEntity task,
            AgentConversationSummaryEntity summary,
            String leaseToken
    ) {
        boolean forced = Boolean.TRUE.equals(task.getForceGeneration());
        return new ChatSummaryTaskClaim(
                task.getId(), task.getTaskId(), task.getTenantId(),
                task.getUserId(), task.getConversationId(),
                task.getRequestedMemoryVersion(),
                task.getRequestedUntilSequence(),
                task.getLastEvaluatedMemoryVersion(),
                forced,
                forced ? com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason
                        .valueOf(task.getForceReason()) : null,
                task.getRetryCount(), leaseToken,
                properties.worker().instanceId(),
                summary == null ? 0 : summary.getSummaryVersion(),
                summary == null ? 0 : summary.getCoveredUntilSequence()
        );
    }

    /** 计算有上限的指数退避，防止持续故障时摘要任务高频冲击数据库和模型服务。 */
    private long exponentialDelayMillis(int retryCount) {
        long initial = properties.retry().initialDelay().toMillis();
        int shift = Math.min(Math.max(retryCount - 1, 0), 30);
        long calculated;
        try {
            calculated = Math.multiplyExact(initial, 1L << shift);
        } catch (ArithmeticException error) {
            calculated = Long.MAX_VALUE;
        }
        return Math.min(calculated,
                properties.retry().maxDelay().toMillis());
    }

    private LocalDateTime nextRetryAt(
            LocalDateTime now,
            int retryCount
    ) {
        long baseMillis = exponentialDelayMillis(retryCount);
        long jitterBound = properties.retry().jitter().toMillis();
        long jitter = jitterBound == 0 ? 0
                : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return now.plusNanos(Math.multiplyExact(
                Math.addExact(baseMillis, jitter), 1_000_000L));
    }

    private static void requireSafeErrorCode(String errorCode) {
        if (errorCode == null
                || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("摘要安全错误码不合法");
        }
    }

    private static void requireSingleTaskUpdate(int updated) {
        if (updated != 1) {
            throw new ChatSummaryStaleWorkException("摘要任务状态已变化");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
