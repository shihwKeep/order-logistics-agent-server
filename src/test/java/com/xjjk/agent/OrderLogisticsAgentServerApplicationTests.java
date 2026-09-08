package com.xjjk.agent;

import com.xjjk.agent.chat.cache.RedisChatHistorySnapshotCache;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.chat.service.memory.ChatHistorySnapshotProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "integration.customer.base-url=http://127.0.0.1:1",
        "integration.customer.internal-token=test-internal-token"
})
class OrderLogisticsAgentServerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private AgentConversationMapper conversationMapper;

    @MockitoBean
    private AgentMessageMapper messageMapper;

    @MockitoBean
    private AgentSummaryTaskMapper summaryTaskMapper;

    @MockitoBean
    private AgentConversationSummaryMapper conversationSummaryMapper;

    @MockitoBean
    private AgentMessageResultMapper messageResultMapper;

    @Test
    void contextLoads() {
        assertThat(context.getBean(ChatHistorySnapshotProvider.class))
                .isNotNull();
        assertThat(context.getBean(RedisChatHistorySnapshotCache.class))
                .isNotNull();
        assertThat(context.getBean(
                "chatHistoryCacheExecutor",
                ThreadPoolTaskExecutor.class
        )).isNotNull();
    }

}
