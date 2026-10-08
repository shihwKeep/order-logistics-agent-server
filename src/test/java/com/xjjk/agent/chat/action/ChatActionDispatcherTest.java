package com.xjjk.agent.chat.action;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
import com.xjjk.agent.aftersale.tool.AfterSaleToolAvailability;
import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.routing.BusinessQueryPlanner;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.stream.FreshBusinessResultGate;
import com.xjjk.agent.chat.service.stream.ChatTurnFinalizer;
import com.xjjk.agent.chat.service.stream.ChatTurnRunner;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.product.service.ProductSearchUnavailableException;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ChatActionDispatcherTest {

    @Mock
    private ChatTurnPreparationService preparationService;
    @Mock
    private ChatContextPreparationService contextService;
    @Mock
    private AiChatService aiChatService;
    @Mock
    private ChatTurnFinalizer finalizer;
    @Mock
    private ChatToolResultRecorder resultRecorder;
    @Mock
    private OrderQueryGateway orderGateway;
    @Mock
    private OrderToolAvailability availability;
    @Mock
    private CustomerOrderQueryService customerOrderQueryService;
    @Mock
    private AfterSaleQueryGateway afterSaleGateway;
    @Mock
    private AfterSaleToolAvailability afterSaleAvailability;
    @Mock
    private ProductSearchGateway productSearchGateway;
    @Mock
    private BusinessQueryPlanner businessQueryPlanner;
    @Mock
    private ChatSseSession session;

    @Test
    void queryProductActionUsesProductGatewayWithoutModel() {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        ProductSearchResult result = new ProductSearchResult(
                "鱼油", 2, 10, 30, true, List.of());
        when(productSearchGateway.search(ProductSearchQuery.of(
                "鱼油", 2, 10))).thenReturn(result);
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);

        ChatActionDispatcher.DispatchResult dispatched = dispatcher.dispatch(
                new ChatActionRequest(
                        "QUERY_PRODUCT", null, null, null, "鱼油", 2),
                identity,
                "request-product");

        verify(productSearchGateway).search(ProductSearchQuery.of(
                "鱼油", 2, 10));
        verifyNoInteractions(orderGateway, customerOrderQueryService, afterSaleGateway);
        org.assertj.core.api.Assertions.assertThat(dispatched.uiResult().kind())
                .isEqualTo("product-list");
        org.assertj.core.api.Assertions.assertThat(dispatched.assistantText())
                .contains("鱼油");
    }

    @Test
    void queryProductActionMapsDownstreamUnavailableToStableBusinessError() {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        when(productSearchGateway.search(ProductSearchQuery.of(
                "1060904801", 1, 10)))
                .thenThrow(new ProductSearchUnavailableException("product service down"));
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);

        assertThatThrownBy(() -> dispatcher.dispatch(
                new ChatActionRequest(
                        "QUERY_PRODUCT", null, null, null, "1060904801"),
                identity,
                "request-product-unavailable"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.CHAT_ACTION_UNAVAILABLE));
    }

    @Test
    void queryOrderActionMapsDownstreamUnavailableToStableBusinessError() {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        when(availability.isLogisticsAvailable(identity)).thenReturn(true);
        when(orderGateway.logistics(
                "XJ202609290001", OrderIdentifierType.ORDER_CODE, identity,
                "request-order-unavailable"))
                .thenThrow(new OrderServiceUnavailableException("order service down"));
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);

        assertThatThrownBy(() -> dispatcher.dispatch(
                new ChatActionRequest("QUERY_ORDER_LOGISTICS", "XJ202609290001"),
                identity,
                "request-order-unavailable"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                            .isEqualTo(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode().message())
                            .isEqualTo("当前业务查询暂时不可用，请稍后重试");
                });
    }

    @Test
    void queryAfterSaleActionMapsDownstreamUnavailableToStableBusinessError() {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        when(afterSaleAvailability.isDetailAvailable(identity)).thenReturn(true);
        when(afterSaleGateway.detail(
                "HH20260414_00002", identity, "request-after-sale-unavailable"))
                .thenThrow(new AfterSaleServiceUnavailableException("after-sale service down"));
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);

        assertThatThrownBy(() -> dispatcher.dispatch(
                new ChatActionRequest(
                        "QUERY_AFTER_SALE_DETAIL", null, null, "HH20260414_00002"),
                identity,
                "request-after-sale-unavailable"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                            .isEqualTo(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(exception.errorCode().message())
                            .isEqualTo("当前业务查询暂时不可用，请稍后重试");
                });
    }

    @Test
    void queryOrderLogisticsActionBypassesModelButKeepsNormalTurnLifecycle()
            throws Exception {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        ChatTurnContext turn = new ChatTurnContext(
                1, 10567, "conversation-1", "request-1",
                "user-message-1", "assistant-message-1", "prompt-v1");
        ChatActionRequest action = new ChatActionRequest(
                "QUERY_ORDER_LOGISTICS", "O123");
        ChatStreamRequest request = new ChatStreamRequest(
                "conversation-1", "查看订单 O123 的物流", action);
        OffsetDateTime queriedAt = OffsetDateTime.parse(
                "2026-09-07T10:15:30+08:00");
        OrderLogisticsResult logistics = new OrderLogisticsResult(
                new OrderLogisticsResult.OrderSummary("O123", 1, "待收货"),
                queriedAt, false, List.of());
        PendingMessageResult pending = new PendingMessageResult(
                1, "get_order_logistics", "logistics-timeline", 1,
                "{\"shipments\":[]}", 16, queriedAt);
        when(preparationService.prepare(
                "conversation-1", identity, "查看订单 O123 的物流"))
                .thenReturn(turn);
        when(availability.isLogisticsAvailable(identity)).thenReturn(true);
        when(orderGateway.logistics(
                "O123", OrderIdentifierType.ORDER_CODE, identity, "request-1"))
                .thenReturn(logistics);
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending);
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);
        ChatTurnRunner runner = new ChatTurnRunner(
                preparationService, contextService, aiChatService,
                finalizer, resultRecorder, dispatcher,
                businessQueryPlanner, new FreshBusinessResultGate(),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.service.ExplicitMemoryCommandService.class),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService.class));

        runner.run(request, identity, new ChatStreamControl(), session, "fallback");

        verify(orderGateway).logistics(
                "O123", OrderIdentifierType.ORDER_CODE, identity, "request-1");
        verify(session).session("conversation-1", "request-1");
        verify(session).queryingLogistics();
        verify(session).result(any(ToolUiResult.class));
        verify(session).delta("已为你查询订单 O123 的最新物流，详细轨迹已展示。");
        verify(contextService, never()).prepare(any(), any(), any());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void queryCustomerOrdersActionUsesSharedResolutionServiceAndBypassesModel()
            throws Exception {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        ChatTurnContext turn = new ChatTurnContext(
                1, 10567, "conversation-1", "request-2",
                "user-message-2", "assistant-message-2", "prompt-v1");
        ChatActionRequest action = new ChatActionRequest(
                "QUERY_CUSTOMER_ORDERS", null, "C001");
        ChatStreamRequest request = new ChatStreamRequest(
                "conversation-1", "查看客户 C001 的订单", action);
        OffsetDateTime queriedAt = OffsetDateTime.parse(
                "2026-09-08T10:15:30+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 0, false, queriedAt, List.of());
        CustomerOrderQueryResult result = new CustomerOrderQueryResult(
                CustomerOrderResolution.FOUND, "C001", "张*", orders);
        PendingMessageResult pending = new PendingMessageResult(
                1, "list_customer_orders", "order-list", 1,
                "{\"items\":[]}", 12, queriedAt);
        when(preparationService.prepare(
                "conversation-1", identity, "查看客户 C001 的订单"))
                .thenReturn(turn);
        when(availability.isCustomerOrderAvailable(identity)).thenReturn(true);
        when(customerOrderQueryService.query("C001", identity, "request-2"))
                .thenReturn(result);
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending);
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);
        ChatTurnRunner runner = new ChatTurnRunner(
                preparationService, contextService, aiChatService,
                finalizer, resultRecorder, dispatcher,
                businessQueryPlanner, new FreshBusinessResultGate(),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.service.ExplicitMemoryCommandService.class),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService.class));

        runner.run(request, identity, new ChatStreamControl(), session, "fallback");

        verify(customerOrderQueryService).query("C001", identity, "request-2");
        verify(session).queryingCustomerOrders();
        verify(session).result(any(ToolUiResult.class));
        verify(session).delta("已为你查询客户 C001 的订单，订单卡片已展示。");
        verify(contextService, never()).prepare(any(), any(), any());
        verifyNoInteractions(aiChatService);
    }

    @Test
    void queryAfterSaleDetailPublishesNewAssistantResultAndBypassesModel()
            throws Exception {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        ChatTurnContext turn = new ChatTurnContext(
                1, 10567, "conversation-1", "request-3",
                "user-message-3", "assistant-message-3", "prompt-v1");
        ChatActionRequest action = new ChatActionRequest(
                "QUERY_AFTER_SALE_DETAIL", null, null, "AS202609080001");
        ChatStreamRequest request = new ChatStreamRequest(
                "conversation-1", "查看售后工单 AS202609080001 的详情", action);
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-08T18:00:00+08:00");
        AfterSaleDetailResult detail = new AfterSaleDetailResult(
                "AS202609080001", 1, "处理中", queriedAt, false, null,
                "张*", "C001", "XJTS01", null, null, null,
                List.of(), List.of(),
                new AfterSaleDetailResult.RefundSummary(0L, 0L, 0L, 0L, 0L),
                false, false, queriedAt);
        PendingMessageResult pending = new PendingMessageResult(
                1, "get_after_sale_detail", "after-sale-detail", 1,
                "{\"afterSaleCode\":\"AS202609080001\"}", 42, queriedAt);
        when(preparationService.prepare(
                "conversation-1", identity, "查看售后工单 AS202609080001 的详情"))
                .thenReturn(turn);
        when(afterSaleAvailability.isDetailAvailable(identity)).thenReturn(true);
        when(afterSaleGateway.detail("AS202609080001", identity, "request-3"))
                .thenReturn(detail);
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending);
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway, customerOrderQueryService, availability,
                afterSaleGateway, afterSaleAvailability, productSearchGateway);
        ChatTurnRunner runner = new ChatTurnRunner(
                preparationService, contextService, aiChatService,
                finalizer, resultRecorder, dispatcher,
                businessQueryPlanner, new FreshBusinessResultGate(),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.service.ExplicitMemoryCommandService.class),
                org.mockito.Mockito.mock(
                        com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService.class));

        runner.run(request, identity, new ChatStreamControl(), session, "fallback");

        verify(afterSaleGateway).detail("AS202609080001", identity, "request-3");
        verify(session).queryingAfterSaleDetail();
        verify(session).result(any(ToolUiResult.class));
        verify(session).delta("已为你查询售后工单 AS202609080001 的详情，详细信息已展示。");
        verify(contextService, never()).prepare(any(), any(), any());
        verifyNoInteractions(aiChatService);
    }
}
