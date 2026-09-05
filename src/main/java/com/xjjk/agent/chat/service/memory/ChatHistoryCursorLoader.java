package com.xjjk.agent.chat.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

/**
 * MySQL 稳定历史游标加载服务。
 *
 * 请求路径会校验会话归属、当前占用和用户消息边界；
 * 预热路径会校验事件版本是否仍与数据库一致。
 * 本服务只访问数据库，不访问 Redis。
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryCursorLoader {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            timeout = 5,
            rollbackFor = Exception.class
    )
    public ChatHistoryCursor loadForRequest(ChatTurnContext turn) {
        Objects.requireNonNull(turn, "本轮上下文不能为空");

        if (!StringUtils.hasText(turn.conversationId())
                || !StringUtils.hasText(turn.requestId())
                || !StringUtils.hasText(turn.userMessageId())) {
            throw new IllegalArgumentException("本轮上下文标识不完整");
        }

        AgentConversationEntity conversation = findConversation(
                turn.tenantId(),
                turn.userId(),
                turn.conversationId()
        );

        if (conversation == null) {
            throw new BusinessException(
                    ApiErrorCode.CONVERSATION_NOT_FOUND
            );
        }

        if (!turn.requestId().equals(
                conversation.getActiveRequestId())) {
            throw new BusinessException(
                    ApiErrorCode.CHAT_REQUEST_INACTIVE
            );
        }

        long memoryVersion = requireMemoryVersion(conversation);
        long memoryUntilSequence = requireMemoryBoundary(conversation);
        long beforeSequence = findCurrentUserSequence(turn);

        if (beforeSequence
                != Math.addExact(memoryUntilSequence, 1L)) {
            throw new IllegalStateException(
                    "当前用户消息序号与稳定历史边界不连续"
            );
        }

        return new ChatHistoryCursor(
                turn.tenantId(),
                turn.userId(),
                turn.conversationId(),
                memoryVersion,
                memoryUntilSequence,
                beforeSequence
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            timeout = 5,
            rollbackFor = Exception.class
    )
    public Optional<ChatHistoryCursor> loadForWarm(
            ChatHistoryChangedEvent event
    ) {
        Objects.requireNonNull(event, "历史变更事件不能为空");

        AgentConversationEntity conversation = findConversation(
                event.tenantId(),
                event.userId(),
                event.conversationId()
        );

        if (conversation == null) {
            return Optional.empty();
        }

        long databaseVersion = requireMemoryVersion(conversation);
        long databaseBoundary = requireMemoryBoundary(conversation);

        if (databaseVersion > event.memoryVersion()) {
            return Optional.empty();
        }

        if (databaseVersion < event.memoryVersion()) {
            throw new IllegalStateException(
                    "数据库稳定历史版本落后于预热事件"
            );
        }

        if (databaseBoundary != event.memoryUntilSequence()) {
            throw new IllegalStateException(
                    "数据库稳定历史边界与预热事件不一致"
            );
        }

        return Optional.of(new ChatHistoryCursor(
                event.tenantId(),
                event.userId(),
                event.conversationId(),
                databaseVersion,
                databaseBoundary,
                Math.addExact(databaseBoundary, 1L)
        ));
    }

    private AgentConversationEntity findConversation(
            long tenantId,
            long userId,
            String conversationId
    ) {
        return conversationMapper.selectOne(
                Wrappers.<AgentConversationEntity>lambdaQuery()
                        .eq(AgentConversationEntity::getConversationId,
                                conversationId)
                        .eq(AgentConversationEntity::getTenantId, tenantId)
                        .eq(AgentConversationEntity::getUserId, userId)
        );
    }

    private long findCurrentUserSequence(ChatTurnContext turn) {
        AgentMessageEntity message = messageMapper.selectOne(
                Wrappers.<AgentMessageEntity>lambdaQuery()
                        .select(AgentMessageEntity::getMessageSequence)
                        .eq(AgentMessageEntity::getTenantId,
                                turn.tenantId())
                        .eq(AgentMessageEntity::getUserId, turn.userId())
                        .eq(AgentMessageEntity::getConversationId,
                                turn.conversationId())
                        .eq(AgentMessageEntity::getRequestId,
                                turn.requestId())
                        .eq(AgentMessageEntity::getMessageId,
                                turn.userMessageId())
                        .eq(AgentMessageEntity::getRole,
                                MessageRole.USER.name())
                        .eq(AgentMessageEntity::getStatus,
                                MessageStatus.SUCCESS.name())
        );

        if (message == null
                || message.getMessageSequence() == null
                || message.getMessageSequence() < 1) {
            throw new IllegalStateException(
                    "本轮用户消息不存在或状态异常"
            );
        }

        return message.getMessageSequence();
    }

    private long requireMemoryVersion(
            AgentConversationEntity conversation
    ) {
        Long value = conversation.getMemoryVersion();
        if (value == null || value < 0) {
            throw new IllegalStateException("会话稳定历史版本异常");
        }
        return value;
    }

    private long requireMemoryBoundary(
            AgentConversationEntity conversation
    ) {
        Long value = conversation.getMemoryUntilSequence();
        if (value == null || value < 0) {
            throw new IllegalStateException("会话稳定历史边界异常");
        }
        return value;
    }
}
