package com.xjjk.agent.chat.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 从会话最早的用户问题生成一次性标题。
 */
@Service
@RequiredArgsConstructor
public class ConversationTitleService {

    private static final String LEGACY_DEFAULT_TITLE = "新对话";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    public void assignFromEarliestQuestion(
            long tenantId,
            long userId,
            String conversationId
    ) {
        String question = messageMapper.selectEarliestUserContent(
                tenantId, userId, conversationId);
        String title = ConversationTitleFormatter.fromQuestion(question);
        if (ConversationTitleFormatter.DEFAULT_TITLE.equals(title)) {
            return;
        }

        conversationMapper.update(
                null,
                Wrappers.<AgentConversationEntity>lambdaUpdate()
                        .eq(AgentConversationEntity::getTenantId, tenantId)
                        .eq(AgentConversationEntity::getUserId, userId)
                        .eq(AgentConversationEntity::getConversationId,
                                conversationId)
                        .in(AgentConversationEntity::getTitle,
                                LEGACY_DEFAULT_TITLE,
                                ConversationTitleFormatter.DEFAULT_TITLE)
                        .set(AgentConversationEntity::getTitle, title)
        );
    }
}
