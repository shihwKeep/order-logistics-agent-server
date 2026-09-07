package com.xjjk.agent.chat.service.turn;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.config.AiPromptProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
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
import java.util.UUID;

/**
 * 一轮问答的开始事务服务。
 *
 * 在短事务内完成会话占用、消息序号分配及消息插入。
 * 禁止在本事务中调用模型或等待流式输出。
 */
@Service
@RequiredArgsConstructor
public class ChatTurnStartService {

    /** 异常占用的恢复判断期限，不是 SSE 超时时间。 */
    private static final long OCCUPANCY_MINUTES = 5L;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AiPromptProperties promptProperties;

    /**
     * 在已有会话中开始一轮问答。
     *
     * 必须通过 Spring 注入的 Bean 调用，使事务代理生效。
     * 当前仅拒绝已有占用，过期占用的恢复逻辑将在后续补充。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatTurnContext begin(
            String conversationId,
            AgentIdentity identity,
            String userText
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        if (!StringUtils.hasText(conversationId)
                || !StringUtils.hasText(userText)
                || userText.length() > 2000) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        // 在归属校验条件下锁定会话，串行分配同一会话的消息序号。
        AgentConversationEntity conversation =
                conversationMapper.selectOne(
                        Wrappers.<AgentConversationEntity>lambdaQuery()
                                .eq(AgentConversationEntity::getConversationId,
                                        conversationId)
                                .eq(AgentConversationEntity::getTenantId,
                                        identity.tenantId())
                                .eq(AgentConversationEntity::getUserId,
                                        identity.userId())
                                // 固定 SQL 片段，不能使用前端传入的内容。
                                .last("FOR UPDATE")
                );

        if (conversation == null) {
            throw new BusinessException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }

        // 不直接清除过期占用，否则旧请求可能与新请求同时写入结果。
        if (conversation.getActiveRequestId() != null
                || conversation.getActiveUntil() != null) {
            throw new BusinessException(ApiErrorCode.CONVERSATION_BUSY);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        String requestId = UUID.randomUUID().toString();
        String promptVersion = promptProperties.version();

        long userSequence =
                Math.addExact(conversation.getLastMessageSequence(), 1L);
        long assistantSequence = Math.addExact(userSequence, 1L);
        // 创建已完整接收的用户消息。
        AgentMessageEntity userMessage =
                newMessage(conversation, requestId, userSequence, now);
        userMessage.setRole(MessageRole.USER.name());
        userMessage.setContent(userText);
        userMessage.setStatus(MessageStatus.SUCCESS.name());

        // 预先创建助手消息，正文随生成完成后的收尾事务更新。
        AgentMessageEntity assistantMessage =
                newMessage(conversation, requestId, assistantSequence, now);
        assistantMessage.setRole(MessageRole.ASSISTANT.name());
        assistantMessage.setContent("");
        assistantMessage.setStatus(MessageStatus.GENERATING.name());
        assistantMessage.setPromptVersion(promptVersion);

        if (messageMapper.insert(userMessage) != 1
                || messageMapper.insert(assistantMessage) != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        // 在同一事务中更新消息序号、历史版本和本轮占用信息。
        int affectedRows = conversationMapper.update(
                null,
                Wrappers.<AgentConversationEntity>lambdaUpdate()
                        .eq(AgentConversationEntity::getConversationId,
                                conversationId)
                        .eq(AgentConversationEntity::getTenantId,
                                identity.tenantId())
                        .eq(AgentConversationEntity::getUserId,
                                identity.userId())
                        .isNull(AgentConversationEntity::getActiveRequestId)
                        .isNull(AgentConversationEntity::getActiveUntil)
                        .set(AgentConversationEntity::getLastMessageSequence,
                                assistantSequence)
                        .set(AgentConversationEntity::getActiveRequestId,
                                requestId)
                        .set(AgentConversationEntity::getActiveUntil,
                                now.plusMinutes(OCCUPANCY_MINUTES))
                        .set(AgentConversationEntity::getUpdatedAt, now)
        );

        if (affectedRows != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        return new ChatTurnContext(
                identity.tenantId(),
                identity.userId(),
                conversationId,
                requestId,
                userMessage.getMessageId(),
                assistantMessage.getMessageId(),
                promptVersion
        );
    }

    /** 初始化一条消息的标识、归属、序号和时间。 */
    private AgentMessageEntity newMessage(
            AgentConversationEntity conversation,
            String requestId,
            long sequence,
            LocalDateTime now
    ) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId(UUID.randomUUID().toString());
        message.setConversationId(conversation.getConversationId());
        message.setTenantId(conversation.getTenantId());
        message.setUserId(conversation.getUserId());
        message.setRequestId(requestId);
        message.setMessageSequence(sequence);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }
}
