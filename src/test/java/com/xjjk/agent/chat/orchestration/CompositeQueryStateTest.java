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
        assertThat(state.resolvedOrderCode()).isEmpty();
        assertThat(state.resolvedOrderAt()).isEmpty();
        assertThat(state.dependencyStatuses()).isEmpty();
        assertThat(state.publishedResultKinds()).isEmpty();
        assertThat(state.toSafeLogData()).doesNotContainKey("identity");
    }

    @Test
    void intentDefaultsToRequiredWithoutDependency() {
        CompositeQueryIntent intent = CompositeQueryIntent.logistics("XJ202609290001");

        assertThat(intent.required()).isTrue();
        assertThat(intent.dependsOnResultKind()).isNull();
    }

    @Test
    void planExcludesOptionalExternalMarkerFromRequiredKinds() {
        CompositeQueryPlan plan = CompositeQueryPlan.withExternalSource(
                List.of(CompositeQueryIntent.order("XJ202609290001"),
                        CompositeQueryIntent.general("是否合理")), true);

        assertThat(plan.requiresExternalSource()).isTrue();
        assertThat(plan.requiredResultKinds())
                .containsExactlyInAnyOrder("order-list", "general-analysis");
    }

    @Test
    void stateStoresOnlySafeBranchSnapshots() {
        CompositeQueryState state = CompositeQueryState.initial(
                "request-1", "conversation-1", "查询物流", IDENTITY,
                CompositeQueryPlan.of(List.of(
                        CompositeQueryIntent.logistics("XJ202609290001"),
                        CompositeQueryIntent.knowledge("物流规则"))));

        CompositeQueryState updated = CompositeQueryState.withBranchSnapshot(
                state, new CompositeQueryBranchSnapshot(
                        "logistics.query", "logistics-timeline", "SUCCESS",
                        "verified summary", null, 1));

        assertThat(updated.branchSnapshots()).hasSize(1);
        assertThat(updated.toSafeLogData()).doesNotContainKey("data");
        assertThat(updated.toSafeLogData()).doesNotContainKey("identity");
    }

    @Test
    void exposesOnlyPublicResolvedOrderFieldsInSafeState() {
        CompositeQueryState state = CompositeQueryState.initial(
                "request-1", "conversation-1", "查询客户最近订单物流", IDENTITY,
                CompositeQueryPlan.of(List.of(
                        CompositeQueryIntent.customerOrders("C1"),
                        CompositeQueryIntent.latestOrderLogistics())));
        CompositeQueryState resolved = new CompositeQueryState(
                CompositeQueryState.update(state, "resolve.latest.order", "SUCCESS",
                        java.util.Map.of(
                                CompositeQueryState.RESOLVED_ORDER_CODE, "XJ001",
                                CompositeQueryState.RESOLVED_ORDER_AT, "2026-08-20 10:00:00",
                                CompositeQueryState.DEPENDENCY_STATUSES,
                                java.util.Map.of("business:logistics-timeline:latest-order", "SUCCESS"),
                                CompositeQueryState.PUBLISHED_RESULT_KINDS,
                                java.util.List.of("order-list"))));

        assertThat(resolved.resolvedOrderCode()).isEqualTo("XJ001");
        assertThat(resolved.resolvedOrderAt()).isEqualTo("2026-08-20 10:00:00");
        assertThat(resolved.dependencyStatuses())
                .containsEntry("business:logistics-timeline:latest-order", "SUCCESS");
        assertThat(resolved.publishedResultKinds()).containsExactly("order-list");
        assertThat(resolved.toSafeLogData())
                .containsEntry("resolvedOrderCode", "XJ001")
                .containsEntry("resolvedOrderAt", "2026-08-20 10:00:00")
                .doesNotContainKey("plan")
                .doesNotContainKey("results");
    }
}
