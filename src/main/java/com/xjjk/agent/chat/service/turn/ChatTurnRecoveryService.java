package com.xjjk.agent.chat.service.turn;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 会话过期占用恢复服务。
 *
 * 将过期且未正常收尾的助手消息标记为中断，
 * 同时释放对应请求的会话占用。
 *
 * 只修改数据库状态，不代表已经停止供应商侧的模型生成。
 */
@Service
@RequiredArgsConstructor
public class ChatTurnRecoveryService {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    /**
     * 尝试恢复当前用户会话中的过期占用。
     *
     * @return true 表示本次完成恢复；
     *         false 表示没有占用或占用尚未到期
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recoverExpired(
            String conversationId,
            AgentIdentity identity
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        if (!StringUtils.hasText(conversationId)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        // 与开始、收尾事务保持一致：先锁会话，再更新消息。
        AgentConversationEntity conversation =
                conversationMapper.selectOne(
                        Wrappers.<AgentConversationEntity>lambdaQuery()
                                .eq(AgentConversationEntity::getConversationId,
                                        conversationId)
                                .eq(AgentConversationEntity::getTenantId,
                                        identity.tenantId())
                                .eq(AgentConversationEntity::getUserId,
                                        identity.userId())
                                .last("FOR UPDATE")
                );

        if (conversation == null) {
            throw new BusinessException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }

        String activeRequestId = conversation.getActiveRequestId();
        LocalDateTime activeUntil = conversation.getActiveUntil();

        if (activeRequestId == null && activeUntil == null) {
            return false;
        }

        // 两个占用字段应同时存在，不对异常数据盲目清空。
        if (!StringUtils.hasText(activeRequestId) || activeUntil == null) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        // 必须在取得会话锁后读取当前时间，避免使用等待锁之前的时间。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        if (activeUntil.isAfter(now)) {
            return false;
        }

        // 保留已落库正文，只改变消息状态及错误信息。
        int messageRows = messageMapper.update(
                null,
                Wrappers.<AgentMessageEntity>lambdaUpdate()
                        .eq(AgentMessageEntity::getTenantId, identity.tenantId())
                        .eq(AgentMessageEntity::getUserId, identity.userId())
                        .eq(AgentMessageEntity::getConversationId,
                                conversationId)
                        .eq(AgentMessageEntity::getRequestId, activeRequestId)
                        .eq(AgentMessageEntity::getRole,
                                MessageRole.ASSISTANT.name())
                        .eq(AgentMessageEntity::getStatus,
                                MessageStatus.GENERATING.name())
                        .set(AgentMessageEntity::getStatus,
                                MessageStatus.INTERRUPTED.name())
                        .set(AgentMessageEntity::getFinishReason, null)
                        .set(AgentMessageEntity::getErrorCode,
                                "CHAT_REQUEST_INTERRUPTED")
                        .set(AgentMessageEntity::getUpdatedAt, now)
        );

        if (messageRows != 1) {
            // 状态不符合预期时回滚，不掩盖消息与占用之间的数据异常。
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        int conversationRows = conversationMapper.update(
                null,
                Wrappers.<AgentConversationEntity>lambdaUpdate()
                        .eq(AgentConversationEntity::getConversationId,
                                conversationId)
                        .eq(AgentConversationEntity::getTenantId,
                                identity.tenantId())
                        .eq(AgentConversationEntity::getUserId,
                                identity.userId())
                        .eq(AgentConversationEntity::getActiveRequestId,
                                activeRequestId)
                        .le(AgentConversationEntity::getActiveUntil, now)
                        .set(AgentConversationEntity::getActiveRequestId, null)
                        .set(AgentConversationEntity::getActiveUntil, null)
                        .set(AgentConversationEntity::getUpdatedAt, now)
        );

        if (conversationRows != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        return true;
    }
}
