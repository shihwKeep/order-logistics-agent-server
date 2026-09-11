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
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.service.conversation.ConversationTitleService;
import com.xjjk.agent.chat.service.summary.ChatSummaryTaskScheduler;
import com.xjjk.agent.common.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnStructuredResultFinishTest {

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
    private ChatTurnFinishService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AgentConversationEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AgentMessageEntity.class);
    }

    @BeforeEach
    void setUp() {
        conversation = new AgentConversationEntity();
        conversation.setConversationId("conversation-1");
        conversation.setTenantId(1L);
        conversation.setUserId(10567L);
        conversation.setActiveRequestId("request-2");
        conversation.setLastMessageSequence(18L);
        conversation.setMemoryVersion(8L);
        conversation.setMemoryUntilSequence(16L);

        turn = new ChatTurnContext(
                1, 10567, "conversation-1", "request-2",
                "user-message-2", "assistant-message-2", "prompt-v2");
        service = new ChatTurnFinishService(
                conversationMapper, messageMapper, resultMapper,
                eventPublisher, summaryTaskScheduler,
                conversationTitleService);
    }

    @Test
    void savesTrustedStructuredResultsBeforeAdvancingConversation() {
        when(conversationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(conversation);
        when(messageMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(resultMapper.insertBatch(anyList())).thenReturn(1);
        when(conversationMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        assertThat(service.finish(
                turn, MessageStatus.SUCCESS, "已查询", "STOP", null,
                List.of(pendingResult()))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentMessageResultEntity>> rows =
                ArgumentCaptor.forClass(List.class);
        verify(resultMapper).insertBatch(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getTenantId()).isEqualTo(1L);
            assertThat(row.getUserId()).isEqualTo(10567L);
            assertThat(row.getConversationId()).isEqualTo("conversation-1");
            assertThat(row.getRequestId()).isEqualTo("request-2");
            assertThat(row.getMessageId()).isEqualTo("assistant-message-2");
            assertThat(row.getResultSequence()).isEqualTo(1);
            assertThat(row.getKind()).isEqualTo("order-list");
            assertThat(row.getPayloadJson()).isEqualTo("{\"items\":[]}");
        });

        InOrder order = inOrder(messageMapper, resultMapper, conversationMapper);
        order.verify(messageMapper).update(any(), any(Wrapper.class));
        order.verify(resultMapper).insertBatch(anyList());
        order.verify(conversationMapper).update(any(), any(Wrapper.class));
        verify(conversationTitleService).assignFromEarliestQuestion(
                1, 10567, "conversation-1");
    }

    @Test
    void resultInsertFailureStopsConversationAdvanceAndTerminalEvents() {
        when(conversationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(conversation);
        when(messageMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(resultMapper.insertBatch(anyList())).thenReturn(0);

        assertThatThrownBy(() -> service.finish(
                turn, MessageStatus.CANCELLED, "部分回答", null,
                "CHAT_CANCELLED", List.of(pendingResult())))
                .isInstanceOf(BusinessException.class);

        verify(conversationMapper, never()).update(any(), any(Wrapper.class));
        verify(summaryTaskScheduler, never()).requestStableHistory(
                any(Long.class), any(Long.class), any(String.class),
                any(Long.class), any(Long.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(conversationTitleService, never())
                .assignFromEarliestQuestion(anyLong(), anyLong(), any(String.class));
    }

    private PendingMessageResult pendingResult() {
        return new PendingMessageResult(
                1, "search_orders", "order-list", 1,
                "{\"items\":[]}", 12,
                OffsetDateTime.parse("2026-09-07T10:15:30+08:00"));
    }
}
