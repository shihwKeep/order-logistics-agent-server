package com.xjjk.agent.customer.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerQueryToolsTest {

    @Test
    void publishesCustomerCardButDoesNotExposeInternalIdToModelOrSseJson() throws Exception {
        CustomerSearchResult result = new CustomerSearchResult(
                CustomerMatchType.CUSTOMER_CODE, 1, false, OffsetDateTime.now(),
                List.of(new CustomerSearchItem(80001L, "C001", "张*",
                        "金卡", "自有", "普通客户")));
        AtomicReference<ToolUiResult> published = new AtomicReference<>();
        CustomerQueryTools tools = new CustomerQueryTools(
                (keyword, type, identity, requestId) -> result);

        String modelText = tools.searchCustomers(" C001 ", "CUSTOMER_CODE",
                context(published));

        assertThat(published.get().toolName()).isEqualTo("search_customers");
        assertThat(published.get().kind()).isEqualTo("customer-list");
        assertThat(published.get().schemaVersion()).isEqualTo(1);
        assertThat(published.get().data().toString()).doesNotContain("80001", "customerId");
        String sseJson = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(published.get().data());
        assertThat(sseJson).contains("\"customerCode\":\"C001\"")
                .doesNotContain("customerId", "80001");
        assertThat(modelText).contains("C001", "张*")
                .doesNotContain("customerId", "80001");
    }

    private ToolContext context(AtomicReference<ToolUiResult> published) {
        AgentToolRequestContext requestContext = new AgentToolRequestContext(
                "request-1", new AgentIdentity(10567L, "10567", "坐席", 23L, 1L),
                published::set, new ToolCallGuard(3));
        return new ToolContext(Map.of(AgentToolRequestContext.CONTEXT_KEY, requestContext));
    }
}
