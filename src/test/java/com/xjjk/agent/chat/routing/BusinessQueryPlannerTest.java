package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.config.BusinessQueryEnforcementProperties;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent.DependencyMode;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent.IdentifierSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
    void keepsOrderDetailsWhenLogisticsAndOrderFactsAreRequestedTogether() {
        BusinessQueryPlan plan = planner.plan(
                "请查询订单 XJTS0120260820000011 的商品、物流状态和订单金额，并结合价格规则与物流停滞规则分别给出判断");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.acceptedResultKinds())
                .contains("order-list", "logistics-timeline", "knowledge-citations");
        assertThat(plan.compositePlan().intents())
                .filteredOn(intent -> intent.source() == CompositeQueryIntent.Source.BUSINESS)
                .extracting(CompositeQueryIntent::resultKind, CompositeQueryIntent::value)
                .containsExactlyInAnyOrder(
                        tuple("order-list", "XJTS0120260820000011"),
                        tuple("logistics-timeline", "XJTS0120260820000011"));
    }

    @Test
    void doesNotTreatOrderCodeAsAfterSaleDetailWhenOnlyAfterSaleRulesAreRequested() {
        BusinessQueryPlan plan = planner.plan(
                "请查询订单 XJTS0120260820000011 的商品和当前物流状态，并结合售后规则说明目前是否具备退货判断依据");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.acceptedResultKinds())
                .contains("order-list", "logistics-timeline", "knowledge-citations")
                .doesNotContain("after-sale-detail");
        assertThat(plan.compositePlan().intents())
                .filteredOn(intent -> intent.resultKind().equals("after-sale-detail"))
                .isEmpty();
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
        assertThat(market.requiresExternalSource()).isTrue();
    }

    @Test
    void productAndPolicyUsesCompositePlan() {
        BusinessQueryPlan plan = planner.plan(
                "查询鱼油商品，并根据企业定价规则判断成交价是否合理");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.acceptedResultKinds())
                .contains("product-list", "knowledge-citations", "general-analysis");
    }

    @Test
    void customerAndAfterSalePolicyUsesCompositePlan() {
        BusinessQueryPlan plan = planner.plan(
                "查询客户 C24101816040001 的订单，并结合售后规则分析是否符合退货条件");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.acceptedResultKinds())
                .contains("order-list", "knowledge-citations", "general-analysis");
    }

    @Test
    void customerLatestOrderLogisticsCreatesDependentIntentWithoutAfterSaleTool() {
        BusinessQueryPlan plan = planner.plan(
                "查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.compositePlan().intents())
                .filteredOn(intent -> intent.source() == CompositeQueryIntent.Source.BUSINESS)
                .extracting(CompositeQueryIntent::resultKind,
                        CompositeQueryIntent::dependsOnResultKind,
                        CompositeQueryIntent::dependencyMode,
                        CompositeQueryIntent::identifierSource)
                .containsExactlyInAnyOrder(
                        tuple("order-list", null, DependencyMode.NONE, IdentifierSource.USER_INPUT),
                        tuple("logistics-timeline", "order-list", DependencyMode.LATEST_ORDER,
                                IdentifierSource.RESOLVED_ORDER));
        assertThat(plan.compositePlan().intents())
                .filteredOn(intent -> "after-sale-detail".equals(intent.resultKind()))
                .isEmpty();
    }

    @Test
    void orderProductAndMarketPriceCreatesUnsupportedExternalMarker() {
        BusinessQueryPlan plan = planner.plan(
                "查询订单 XJTS0120260820000011 的商品，并分析当前市场价格区间");

        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
        assertThat(plan.requiresExternalSource()).isTrue();
        assertThat(plan.acceptedResultKinds()).contains("external-data-unavailable");
        assertThat(plan.compositePlan().intents())
                .filteredOn(intent -> intent.source() == CompositeQueryIntent.Source.BUSINESS)
                .extracting(CompositeQueryIntent::resultKind, CompositeQueryIntent::value)
                .containsExactly(tuple("order-list", "XJTS0120260820000011"));
        assertThat(plan.acceptedResultKinds())
                .doesNotContain("product-list")
                .contains("order-list", "general-analysis", "external-data-unavailable");
    }

    @Test
    void missingOrderIdentifierStillClarifiesBeforeCompositeRouting() {
        assertThat(planner.plan("查询订单物流并根据规则判断").mode())
                .isEqualTo(BusinessQueryMode.CLARIFICATION);
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
