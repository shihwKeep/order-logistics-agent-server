package com.xjjk.agent.chat.service.turn;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.chat.service.conversation.ConversationTitleService;
import com.xjjk.agent.chat.service.memory.ChatHistoryChangedEvent;
import com.xjjk.agent.chat.service.summary.ChatSummaryTaskScheduler;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryChangedEventPublishingTest {

    @Mock
    private AgentConversationMapper conversationMapper;

    @Mock
    private AgentMessageMapper messageMapper;

    @Mock
    private AgentMessageResultMapper resultMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ChatSummaryTaskScheduler summaryTaskScheduler;

    @Mock
    private ConversationTitleService conversationTitleService;

    private AgentConversationEntity conversation;
    private ChatTurnContext turn;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new MybatisConfiguration(),
                        "test"
                ),
                AgentConversationEntity.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new MybatisConfiguration(),
                        "test"
                ),
                AgentMessageEntity.class
        );
    }

    @BeforeEach
    void setUp() {
        conversation = new AgentConversationEntity();
        conversation.setConversationId("conversation-1");
        conversation.setTenantId(1L);
        conversation.setUserId(10567L);
        conversation.setActiveRequestId("request-2");
        conversation.setActiveUntil(LocalDateTime.of(
                2000, 1, 1, 0, 0));
        conversation.setLastMessageSequence(18L);
        conversation.setMemoryVersion(8L);
        conversation.setMemoryUntilSequence(16L);

        turn = new ChatTurnContext(
                1,
                10567,
                "conversation-1",
                "request-2",
                "user-message-2",
                "assistant-message-2",
                "prompt-v2"
        );
    }

    @Test
    void successfulFinishPublishesNewStableCursor() {
        when(conversationMapper.selectOne(
                any(Wrapper.class))).thenReturn(conversation);
        when(messageMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);
        when(conversationMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);
        ChatTurnFinishService service = new ChatTurnFinishService(
                conversationMapper,
                messageMapper,
                resultMapper,
                eventPublisher,
                summaryTaskScheduler,
                conversationTitleService
        );

        assertThat(service.finish(
                turn,
                MessageStatus.SUCCESS,
                "answer",
                "STOP",
                null,
                List.of()
        )).isTrue();

        ArgumentCaptor<ChatHistoryChangedEvent> event =
                ArgumentCaptor.forClass(ChatHistoryChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        verify(summaryTaskScheduler).requestStableHistory(
                1, 10567, "conversation-1", 9, 18
        );
        verify(conversationTitleService).assignFromEarliestQuestion(
                1, 10567, "conversation-1");
        assertThat(event.getValue()).isEqualTo(
                new ChatHistoryChangedEvent(
                        1, 10567, "conversation-1", 9, 18)
        );
    }

    @Test
    void failedConversationUpdateDoesNotPublishEvent() {
        when(conversationMapper.selectOne(
                any(Wrapper.class))).thenReturn(conversation);
        when(messageMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);
        when(conversationMapper.update(any(), any(Wrapper.class)))
                .thenReturn(0);
        ChatTurnFinishService service = new ChatTurnFinishService(
                conversationMapper,
                messageMapper,
                resultMapper,
                eventPublisher,
                summaryTaskScheduler,
                conversationTitleService
        );

        assertThatThrownBy(() -> service.finish(
                turn,
                MessageStatus.SUCCESS,
                "answer",
                "STOP",
                null,
                List.of()
        )).isInstanceOf(BusinessException.class);
        verify(eventPublisher, never()).publishEvent(any());
        verify(summaryTaskScheduler, never()).requestStableHistory(
                anyLong(),
                anyLong(),
                any(String.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void successfulRecoveryPublishesNewStableCursor() {
        when(conversationMapper.selectOne(
                any(Wrapper.class))).thenReturn(conversation);
        when(messageMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);
        when(conversationMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);
        ChatTurnRecoveryService service = new ChatTurnRecoveryService(
                conversationMapper,
                messageMapper,
                eventPublisher,
                summaryTaskScheduler
        );
        AgentIdentity identity = new AgentIdentity(
                10567,
                "account",
                "name",
                3673,
                1
        );

        assertThat(service.recoverExpired(
                "conversation-1",
                identity
        )).isTrue();

        ArgumentCaptor<ChatHistoryChangedEvent> event =
                ArgumentCaptor.forClass(ChatHistoryChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        verify(summaryTaskScheduler).requestStableHistory(
                1, 10567, "conversation-1", 9, 18
        );
        assertThat(event.getValue()).isEqualTo(
                new ChatHistoryChangedEvent(
                        1, 10567, "conversation-1", 9, 18)
        );
    }
}
