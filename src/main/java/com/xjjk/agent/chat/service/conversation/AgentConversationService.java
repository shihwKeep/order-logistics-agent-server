package com.xjjk.agent.chat.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 会话服务。
 *
 * 负责创建会话，以及校验已有会话的租户和用户归属。
 * 不负责调用模型，也不负责保存消息。
 */
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    private final AgentConversationMapper conversationMapper;

    /**
     * 为当前已认证用户创建会话。
     *
     * 租户、用户和组织信息全部来自后端认证结果，
     * 不接受前端自行指定归属。
     */
    public AgentConversationEntity create(AgentIdentity identity) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        // 与数据库 DATETIME(3) 的毫秒精度保持一致，统一使用 UTC。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        AgentConversationEntity conversation = new AgentConversationEntity();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setTenantId(identity.tenantId());
        conversation.setUserId(identity.userId());
        conversation.setOrgId(identity.orgId());
        conversation.setTitle("新对话");
        conversation.setLastMessageSequence(0L);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        // 新会话尚未开始生成，activeRequestId 和 activeUntil 保持为空。
        int affectedRows = conversationMapper.insert(conversation);
        if (affectedRows != 1) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        return conversation;
    }

    /**
     * 查询当前用户拥有的会话。
     *
     * 必须同时满足会话 ID、租户 ID、用户 ID 三个条件。
     * 查不到时直接拒绝，不自动创建替代会话。
     */
    public AgentConversationEntity requireOwned(
            String conversationId,
            AgentIdentity identity
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        if (!StringUtils.hasText(conversationId)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        AgentConversationEntity conversation =
                conversationMapper.selectOne(
                        Wrappers.<AgentConversationEntity>lambdaQuery()
                                .eq(
                                        AgentConversationEntity::getConversationId,
                                        conversationId
                                )
                                .eq(
                                        AgentConversationEntity::getTenantId,
                                        identity.tenantId()
                                )
                                .eq(
                                        AgentConversationEntity::getUserId,
                                        identity.userId()
                                )
                );

        if (conversation == null) {
            throw new BusinessException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }

        return conversation;
    }
}
