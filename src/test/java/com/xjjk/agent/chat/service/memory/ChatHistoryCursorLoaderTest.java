package com.xjjk.agent.chat.service.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ChatHistoryCursorLoaderTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "test"),
                AgentConversationEntity.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "test"),
                AgentMessageEntity.class
        );
    }

    @Mock
    private AgentConversationMapper conversationMapper;

    @Mock
    private AgentMessageMapper messageMapper;

    private ChatHistoryCursorLoader loader;
    private AgentConversationEntity conversation;
    private AgentMessageEntity currentMessage;
    private ChatTurnContext turn;

    @BeforeEach
    void setUp() {
        loader = new ChatHistoryCursorLoader(
                conversationMapper,
                messageMapper
        );
        conversation = new AgentConversationEntity();
        conversation.setConversationId("conversation-1");
        conversation.setTenantId(1L);
        conversation.setUserId(10567L);
        conversation.setMemoryVersion(8L);
        conversation.setMemoryUntilSequence(16L);
        conversation.setActiveRequestId("request-2");

        currentMessage = new AgentMessageEntity();
        currentMessage.setMessageSequence(17L);
        currentMessage.setRole(MessageRole.USER.name());
        currentMessage.setStatus(MessageStatus.SUCCESS.name());

        turn = new ChatTurnContext(
                1,
                10567,
                "conversation-1",
                "request-2",
                "user-message-2",
                "assistant-message-2",
                "prompt-v2"
        );

        when(conversationMapper.selectOne(
                any(Wrapper.class))).thenReturn(conversation);
        lenient().when(messageMapper.selectOne(
                any(Wrapper.class))).thenReturn(currentMessage);
    }

    @Test
    void requestCursorUsesDatabaseStableBoundary() {
        assertThat(loader.loadForRequest(turn)).isEqualTo(
                new ChatHistoryCursor(
                        1,
                        10567,
                        "conversation-1",
                        8,
                        16,
                        17
                )
        );
    }

    @Test
    void differentActiveRequestIsRejected() {
        conversation.setActiveRequestId("other-request");

        assertThatThrownBy(() -> loader.loadForRequest(turn))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ApiErrorCode.CHAT_REQUEST_INACTIVE)
                );
    }

    @Test
    void currentMessageOutsideStableBoundaryIsRejected() {
        currentMessage.setMessageSequence(18L);

        assertThatThrownBy(() -> loader.loadForRequest(turn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不连续");
    }

    @Test
    void matchingWarmEventReturnsStableCursor() {
        ChatHistoryChangedEvent event = new ChatHistoryChangedEvent(
                1, 10567, "conversation-1", 8, 16);

        assertThat(loader.loadForWarm(event)).contains(
                new ChatHistoryCursor(
                        1,
                        10567,
                        "conversation-1",
                        8,
                        16,
                        17
                )
        );
    }

    @Test
    void obsoleteWarmEventIsSkipped() {
        ChatHistoryChangedEvent event = new ChatHistoryChangedEvent(
                1, 10567, "conversation-1", 7, 14);

        Optional<ChatHistoryCursor> result = loader.loadForWarm(event);

        assertThat(result).isEmpty();
    }

    @Test
    void databaseVersionBehindWarmEventIsRejected() {
        ChatHistoryChangedEvent event = new ChatHistoryChangedEvent(
                1, 10567, "conversation-1", 9, 18);

        assertThatThrownBy(() -> loader.loadForWarm(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("落后");
    }
}
