package com.xjjk.agent.evaluation;

import com.xjjk.agent.chat.orchestration.CompositeQueryService;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.tool.ToolUiResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

final class EvaluationAssertions {

    private EvaluationAssertions() {
    }

    static void assertCase(
            GoldenEvaluationCase evaluationCase,
            GoldenCaseExecution.Execution execution) {
        CompositeQueryService.CompositeQueryResult result = execution.result();
        GoldenEvaluationCase.Expectation expect = evaluationCase.expect();
        assertStatus(evaluationCase, result, expect.status());
        if ("NOT_FOUND".equals(expect.status())) {
            assertThat(result.actualResultKinds())
                    .doesNotContain("knowledge-citations");
            assertEmptyBusinessResult(result);
        } else {
            assertThat(result.actualResultKinds())
                    .containsExactlyInAnyOrderElementsOf(expect.resultKinds());
        }
        verify(execution.knowledgeGateway(),
                times(expect.knowledgeCalls()))
                .retrieve(anyString(), any(), any(), anyString());
        for (String forbidden : expect.forbiddenCalls()) {
            verifyForbidden(forbidden, execution);
        }
        if (Boolean.TRUE.equals(expect.sensitiveValuesAbsent())) {
            assertNoSensitiveValues(result);
        }
    }

    private static void assertStatus(
            GoldenEvaluationCase evaluationCase,
            CompositeQueryService.CompositeQueryResult result,
            String expectedStatus) {
        if ("NOT_FOUND".equals(expectedStatus)) {
            assertThat(result.status())
                    .as("case %s", evaluationCase.caseId())
                    .isIn("NOT_FOUND", "PARTIAL_SUCCESS", "SUCCESS");
            return;
        }
        assertThat(result.status())
                .as("case %s", evaluationCase.caseId())
                .isEqualTo(expectedStatus);
    }

    private static void assertEmptyBusinessResult(
            CompositeQueryService.CompositeQueryResult result) {
        assertThat(result.uiResults())
                .as("not-found case must publish an explicit empty business result")
                .isNotEmpty()
                .allSatisfy(uiResult -> assertEmptyData(uiResult));
    }

    private static void assertEmptyData(ToolUiResult uiResult) {
        Object data = uiResult.data();
        if (data instanceof OrderSearchResult orders) {
            assertThat(orders.total()).isZero();
        } else if (data instanceof OrderLogisticsResult logistics) {
            assertThat(logistics.shipments()).isEmpty();
        } else if (data instanceof CustomerOrderQueryResult customerOrders) {
            assertThat(customerOrders.orders()).isNull();
        } else if (data instanceof ProductSearchResult products) {
            assertThat(products.items()).isEmpty();
        } else if (data != null) {
            throw new AssertionError("not-found case returned non-empty business data: "
                    + uiResult.kind());
        }
    }

    private static void verifyForbidden(
            String forbidden,
            GoldenCaseExecution.Execution execution) {
        switch (forbidden) {
            case "knowledge" -> verify(execution.knowledgeGateway(), never())
                    .retrieve(anyString(), any(), any(), anyString());
            case "order-logistics" -> verify(execution.orderGateway(), never())
                    .logistics(anyString(), any(), any(), anyString());
            case "customer-order" -> verify(execution.customerOrders(), never())
                    .query(anyString(), any(), anyString());
            case "product" -> verify(execution.productGateway(), never())
                    .search(any());
            default -> throw new AssertionError("未知禁止调用: " + forbidden);
        }
    }

    private static void assertNoSensitiveValues(
            CompositeQueryService.CompositeQueryResult result) {
        String text = (result.verifiedAnswerContext() == null ? ""
                : result.verifiedAnswerContext()) + " "
                + (result.safeMessage() == null ? "" : result.safeMessage());
        assertThat(text)
                .doesNotContain("Authorization", "Bearer ", "Token", "customerId",
                        "password", "cookie", "198****2170");
    }
}
