package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeQueryStateTest {

    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "account", "name", 3673L, 1L);

    @Test
    void initialStateContainsOnlySafeOrchestrationFields() {
        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("物流规则")));

        CompositeQueryState state = CompositeQueryState.initial(
                "request-1", "conversation-1", "查询物流", IDENTITY, plan);

        assertThat(state.requestId()).isEqualTo("request-1");
        assertThat(state.conversationId()).isEqualTo("conversation-1");
        assertThat(state.requiredResultKinds())
                .containsExactlyInAnyOrder("logistics-timeline", "knowledge-citations");
        assertThat(state.finalStatus()).isEqualTo("PENDING");
        assertThat(state.toSafeLogData()).doesNotContainKey("identity");
    }
}
