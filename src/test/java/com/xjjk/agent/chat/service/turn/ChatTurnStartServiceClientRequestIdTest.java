package com.xjjk.agent.chat.service.turn;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xjjk.agent.chat.config.AiPromptProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnStartServiceClientRequestIdTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AgentConversationEntity.class);
    }

    @Test
    void usesClientRequestIdForBothMessagesAndConversationOccupancy() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversationEntity conversation = new AgentConversationEntity();
        conversation.setConversationId("conversation-1");
        conversation.setTenantId(1L);
        conversation.setUserId(2L);
        conversation.setOrgId(3L);
        conversation.setLastMessageSequence(0L);
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.insert(any(AgentMessageEntity.class))).thenReturn(1);
        ChatTurnStartService service = new ChatTurnStartService(
                conversationMapper,
                messageMapper,
                new AiPromptProperties("prompt-v1", "system"));
        AgentIdentity identity = new AgentIdentity(
                2L, "agent", "坐席", 3L, 1L);
        String clientRequestId = "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

        ChatTurnContext turn = service.begin(
                "conversation-1", identity, "你好", clientRequestId);

        assertThat(turn.requestId()).isEqualTo(clientRequestId);
        ArgumentCaptor<AgentMessageEntity> messages =
                ArgumentCaptor.forClass(AgentMessageEntity.class);
        verify(messageMapper, times(2)).insert(messages.capture());
        List<AgentMessageEntity> inserted = messages.getAllValues();
        assertThat(inserted)
                .extracting(AgentMessageEntity::getRequestId)
                .containsExactly(clientRequestId, clientRequestId);
    }
}
