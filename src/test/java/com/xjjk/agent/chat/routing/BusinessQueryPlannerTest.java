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
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("介绍一下订单状态是什么意思").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("查一下客户 C1 的订单").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("查询订单 XJTS0120260820000011 和售后").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
    }

    @Test
    void requiresGroundedKnowledgeForRulesPoliciesAndProcesses() {
        assertThat(planner.plan("售后退款规则是什么").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("物流异常应该按什么流程处理").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("公司的发货政策有哪些").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("退款期限是几天").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("签收后多久可以退货").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("哪些情况不能退款").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("发票怎么开").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("运费怎么算").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("公司明年春节期间物流异常的特殊赔偿金额是多少？")
                .acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("商品怎么上架").acceptedResultKinds())
                .containsExactly("knowledge-citations");
        assertThat(planner.plan("介绍一下你自己"))
                .isEqualTo(BusinessQueryPlan.general());
    }

    @Test
    void keepsIdentifiedRealtimeQueriesOutOfTheKnowledgePath() {
        assertThat(planner.plan("订单 XJTS0120260820000011 现在什么状态")
                .acceptedResultKinds()).containsExactly("order-list");
        assertDirect("查看订单 XJTS0120260820000011 的物流",
                "QUERY_ORDER_LOGISTICS", "logistics-timeline");
        assertThat(planner.plan("鱼油还有库存吗").acceptedResultKinds())
                .containsExactly("product-list");
    }

    @Test
    void routesBusinessFactAndEnterpriseRuleToCompositeWorkflow() {
        BusinessQueryPlan plan = planner.plan(
                "查询订单 XJTS0120260820000011 的最新物流，并根据物流规则判断是否需要预警");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.acceptedResultKinds())
                .containsExactlyInAnyOrder("logistics-timeline", "knowledge-citations");
        assertThat(plan.compositePlan()).isNotNull();
    }

    @Test
    void keepsRealtimeLogisticsQuestionWithPlaceholderOutOfKnowledgePath() {
        BusinessQueryPlan plan = planner.plan(
                "请查询订单【完整订单号】当前物流状态，并根据物流停滞规则判断客服应该如何处理");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.CLARIFICATION);
        assertThat(plan.clarificationMessage()).contains("完整订单号");
        assertThat(plan.acceptedResultKinds()).isEmpty();
        assertThat(plan.compositePlan()).isNull();
    }

    @Test
    void keepsRuleOnlyQuestionOnKnowledgePath() {
        BusinessQueryPlan plan = planner.plan("物流轨迹超过24小时没有更新应该怎么处理");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(plan.acceptedResultKinds()).containsExactly("knowledge-citations");
        assertThat(plan.compositePlan()).isNull();
    }

    @Test
    void leavesOrdinaryAnalysisGeneralAndDoesNotInventExternalSource() {
        BusinessQueryPlan ordinary = planner.plan("请总结客服回答的注意事项");
        assertThat(ordinary.mode()).isEqualTo(BusinessQueryMode.GENERAL);

        BusinessQueryPlan market = planner.plan(
                "订单 XJTS0120260820000011 中的商品当前市场价格区间是多少");
        assertThat(market.requiresExternalSource()).isFalse();
    }

    @Test
    void disablesPlanningThroughTheNacosEmergencySwitch() {
        BusinessQueryPlanner disabled = new BusinessQueryPlanner(
                new BusinessQueryEnforcementProperties(false));
        assertThat(disabled.plan("查看售后工单 HH20260414_00002"))
                .isEqualTo(BusinessQueryPlan.general());
        assertThat(disabled.plan("售后退款规则是什么").acceptedResultKinds())
                .containsExactly("knowledge-citations");
    }

    private void assertDirect(
            String message, String actionType, String expectedKind) {
        BusinessQueryPlan plan = planner.plan(message);
        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.DIRECT);
        assertThat(plan.directAction().type()).isEqualTo(actionType);
        assertThat(plan.acceptedResultKinds()).containsExactly(expectedKind);
    }
}
