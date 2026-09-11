package com.xjjk.agent.chat.service.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationTitleServiceTest {

    @Mock
    private AgentConversationMapper conversationMapper;

    @Mock
    private AgentMessageMapper messageMapper;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AgentConversationEntity.class);
    }

    @Test
    void assignsFormattedEarliestQuestionWithinOwnerBoundary() {
        when(messageMapper.selectEarliestUserContent(
                1, 10567, "conversation-1"))
                .thenReturn("  第一次失败的问题内容超过十五个字符需要被截断  ");
        when(conversationMapper.update(isNull(), any(Wrapper.class)))
                .thenReturn(1);
        ConversationTitleService service = new ConversationTitleService(
                conversationMapper, messageMapper);

        service.assignFromEarliestQuestion(1, 10567, "conversation-1");

        verify(messageMapper).selectEarliestUserContent(
                1, 10567, "conversation-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentConversationEntity>> update =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment())
                .contains("tenant_id", "user_id", "conversation_id", "title");
        assertThat(((AbstractWrapper<?, ?, ?>) update.getValue())
                .getParamNameValuePairs().values())
                .contains(1L, 10567L, "conversation-1", "新对话", "新会话",
                        "第一次失败的问题内容超过十五个...");
    }

    @Test
    void skipsUpdateWhenEarliestQuestionIsBlank() {
        when(messageMapper.selectEarliestUserContent(
                1, 10567, "conversation-1"))
                .thenReturn(" \n\t ");
        ConversationTitleService service = new ConversationTitleService(
                conversationMapper, messageMapper);

        service.assignFromEarliestQuestion(1, 10567, "conversation-1");

        verify(conversationMapper, never()).update(isNull(), any(Wrapper.class));
    }
}
