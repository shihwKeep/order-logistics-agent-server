package com.xjjk.agent.chat.service.turn;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.service.memory.ChatHistoryChangedEvent;
import com.xjjk.agent.chat.service.summary.ChatSummaryTaskScheduler;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 一轮问答的收尾事务服务。
 *
 * 原子地保存助手最终结果，并释放会话占用。
 * 使用请求 ID 校验收尾资格，阻止旧请求覆盖新请求。
 */
@Service
@RequiredArgsConstructor
public class ChatTurnFinishService {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatSummaryTaskScheduler summaryTaskScheduler;

    /**
     * 尝试结束本轮问答。
     *
     * @param turn 开始事务生成的内部上下文，不能由前端构造
     * @param status 最终消息状态，不能是 GENERATING
     * @param content 已生成的正文，失败时允许保存部分内容
     * @param finishReason 模型结束原因，未知时为空
     * @param errorCode 安全的业务错误码，不传入原始异常内容
     * @return true 表示本次完成收尾；
     *         false 表示本次没有写入，可能已收尾或已失去占用资格
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finish(
            ChatTurnContext turn,
            MessageStatus status,
            String content,
            String finishReason,
            String errorCode
    ) {
        Objects.requireNonNull(turn, "本轮上下文不能为空");
        Objects.requireNonNull(status, "消息状态不能为空");

        if (status == MessageStatus.GENERATING) {
            throw new IllegalArgumentException("收尾状态不能为 GENERATING");
        }

        if (!StringUtils.hasText(turn.requestId())
                || !StringUtils.hasText(turn.conversationId())
                || !StringUtils.hasText(turn.assistantMessageId())) {
            throw new IllegalArgumentException("本轮上下文标识不完整");
        }

        if (status == MessageStatus.SUCCESS
                && !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("成功回答的正文不能为空");
        }

        if ((finishReason != null && finishReason.length() > 64)
                || (errorCode != null && errorCode.length() > 64)) {
            throw new IllegalArgumentException("结束原因或错误码超过字段长度");
        }

        // 与开始事务保持相同的加锁顺序：先会话，再消息。
        AgentConversationEntity conversation =
                conversationMapper.selectOne(
                        Wrappers.<AgentConversationEntity>lambdaQuery()
                                .eq(AgentConversationEntity::getConversationId,
                                        turn.conversationId())
                                .eq(AgentConversationEntity::getTenantId,
                                        turn.tenantId())
                                .eq(AgentConversationEntity::getUserId,
                                        turn.userId())
                                .last("FOR UPDATE")
                );

        // 已释放占用，或占用者已经变更时，不再写入任何结果。
        if (conversation == null
                || !turn.requestId().equals(
                conversation.getActiveRequestId())) {
            return false;
        }

        // 已锁定会话并确认本轮仍拥有收尾资格。
        Long currentMemoryVersion = conversation.getMemoryVersion();
        Long lastMessageSequence = conversation.getLastMessageSequence();
        Long currentMemoryUntilSequence =
                conversation.getMemoryUntilSequence();

        if (currentMemoryVersion == null
                || currentMemoryVersion < 0
                || lastMessageSequence == null
                || lastMessageSequence < 1
                || currentMemoryUntilSequence == null
                || currentMemoryUntilSequence < 0
                || currentMemoryUntilSequence > lastMessageSequence) {
            throw new IllegalStateException("会话稳定历史游标异常");
        }

        long nextMemoryVersion =
                Math.addExact(currentMemoryVersion, 1L);

        /*
         * 当前请求独占该会话，而且开始事务已经把
         * lastMessageSequence 更新为当前助手消息序号。
         * 因此收尾成功后，该序号成为新的稳定历史边界。
         */
        long nextMemoryUntilSequence = lastMessageSequence;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        // 只允许将本轮助手消息从 GENERATING 转为最终状态。
        int messageRows = messageMapper.update(
                null,
                Wrappers.<AgentMessageEntity>lambdaUpdate()
                        .eq(AgentMessageEntity::getTenantId, turn.tenantId())
                        .eq(AgentMessageEntity::getUserId, turn.userId())
                        .eq(AgentMessageEntity::getConversationId,
                                turn.conversationId())
                        .eq(AgentMessageEntity::getRequestId, turn.requestId())
                        .eq(AgentMessageEntity::getMessageId,
                                turn.assistantMessageId())
                        .eq(AgentMessageEntity::getRole,
                                MessageRole.ASSISTANT.name())
                        .eq(AgentMessageEntity::getStatus,
                                MessageStatus.GENERATING.name())
                        .set(AgentMessageEntity::getContent,
                                content == null ? "" : content)
                        .set(AgentMessageEntity::getStatus, status.name())
                        .set(AgentMessageEntity::getFinishReason, finishReason)
                        .set(AgentMessageEntity::getErrorCode,
                                status == MessageStatus.SUCCESS ? null : errorCode)
                        .set(AgentMessageEntity::getUpdatedAt, now)
        );

        if (messageRows != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        // 保存消息、推进稳定历史游标和释放占用属于同一个事务，不能只成功一部分。
        int conversationRows = conversationMapper.update(
                null,
                Wrappers.<AgentConversationEntity>lambdaUpdate()
                        .eq(AgentConversationEntity::getConversationId,
                                turn.conversationId())
                        .eq(AgentConversationEntity::getTenantId, turn.tenantId())
                        .eq(AgentConversationEntity::getUserId, turn.userId())
                        .eq(AgentConversationEntity::getActiveRequestId,
                                turn.requestId())
                        .set(AgentConversationEntity::getActiveRequestId, null)
                        .set(AgentConversationEntity::getActiveUntil, null)
                        .set(AgentConversationEntity::getMemoryVersion,
                                nextMemoryVersion)
                        .set(AgentConversationEntity::getMemoryUntilSequence,
                                nextMemoryUntilSequence)
                        .set(AgentConversationEntity::getUpdatedAt, now)
        );

        if (conversationRows != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        /*
         * 摘要这里只登记持久化目标，不调用模型。
         * 登记与消息终态、稳定历史游标处于同一事务，任何一步失败都会整体回滚，
         * 防止摘要任务看到数据库中并不存在的稳定历史版本。
         */
        summaryTaskScheduler.requestStableHistory(
                turn.tenantId(),
                turn.userId(),
                turn.conversationId(),
                nextMemoryVersion,
                nextMemoryUntilSequence
        );

        // 回答消息和稳定历史游标已经在当前事务中更新完成，此处发布“历史已推进”事件。
        // 事件监听器使用 AFTER_COMMIT：只有本方法事务真正提交成功后才会收到事件；
        // 监听器随后把预热任务提交到专用线程池，异步重建并写入新版本 Redis 快照。
        // 如果本方法后续抛出异常并回滚，监听器不会执行，也不会缓存未提交的数据。
        eventPublisher.publishEvent(new ChatHistoryChangedEvent(
                turn.tenantId(),
                turn.userId(),
                turn.conversationId(),
                nextMemoryVersion,
                nextMemoryUntilSequence
        ));

        return true;
    }
}
