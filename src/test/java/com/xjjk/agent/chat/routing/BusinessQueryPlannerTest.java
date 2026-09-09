package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.config.BusinessQueryEnforcementProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessQueryPlannerTest {

    private final BusinessQueryPlanner planner = new BusinessQueryPlanner(
            new BusinessQueryEnforcementProperties(true));

    @Test
    void routesOnlyHighConfidenceCompleteRequestsDirectly() {
        assertDirect("查看订单 XJTS0120260820000011 的物流",
                "QUERY_ORDER_LOGISTICS", "logistics-timeline");
        assertDirect("帮忙查下客户 C24101816040001 的订单",
                "QUERY_CUSTOMER_ORDERS", "order-list");
        assertDirect("查看售后工单 HH20260414_00002",
                "QUERY_AFTER_SALE_DETAIL", "after-sale-detail");
    }

    @Test
    void keepsNaturalLanguageBusinessQueriesOnTheGuardedModelPath() {
        assertThat(planner.plan("查询鱼油商品"))
                .extracting(BusinessQueryPlan::mode)
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("查询鱼油商品").acceptedResultKinds())
                .containsExactly("product-list");
        assertThat(planner.plan("查询客户张三的信息").acceptedResultKinds())
                .containsExactly("customer-list");
        assertThat(planner.plan("查询张三本月的售后").acceptedResultKinds())
                .containsExactly("after-sale-list");
        assertThat(planner.plan("查询订单 XJTS0120260820000011").acceptedResultKinds())
                .containsExactly("order-list");
        assertThat(planner.plan("鱼油还有库存吗").acceptedResultKinds())
                .containsExactly("product-list");
        assertThat(planner.plan("订单 XJTS0120260820000011 现在什么状态").acceptedResultKinds())
                .containsExactly("order-list");
    }

    @Test
    void leavesGeneralConversationStreamingAndDoesNotGuessAmbiguousDirectCommands() {
        assertThat(planner.plan("你好").mode()).isEqualTo(BusinessQueryMode.GENERAL);
        assertThat(planner.plan("介绍一下订单状态是什么意思").mode())
                .isEqualTo(BusinessQueryMode.GENERAL);
        assertThat(planner.plan("查一下客户 C1 的订单").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("查询订单 XJTS0120260820000011 和售后").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
    }

    @Test
    void disablesPlanningThroughTheNacosEmergencySwitch() {
        BusinessQueryPlanner disabled = new BusinessQueryPlanner(
                new BusinessQueryEnforcementProperties(false));
        assertThat(disabled.plan("查看售后工单 HH20260414_00002"))
                .isEqualTo(BusinessQueryPlan.general());
    }

    private void assertDirect(
            String message, String actionType, String expectedKind) {
        BusinessQueryPlan plan = planner.plan(message);
        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.DIRECT);
        assertThat(plan.directAction().type()).isEqualTo(actionType);
        assertThat(plan.acceptedResultKinds()).containsExactly(expectedKind);
    }
}
