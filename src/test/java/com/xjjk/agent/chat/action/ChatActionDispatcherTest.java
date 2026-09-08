package com.xjjk.agent.chat.action;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
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
import com.xjjk.agent.order.tool.OrderToolAvailability;
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
    private ChatSseSession session;

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
                orderGateway, customerOrderQueryService, availability);
        ChatTurnRunner runner = new ChatTurnRunner(
                preparationService, contextService, aiChatService,
                finalizer, resultRecorder, dispatcher);

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
                orderGateway, customerOrderQueryService, availability);
        ChatTurnRunner runner = new ChatTurnRunner(
                preparationService, contextService, aiChatService,
                finalizer, resultRecorder, dispatcher);

        runner.run(request, identity, new ChatStreamControl(), session, "fallback");

        verify(customerOrderQueryService).query("C001", identity, "request-2");
        verify(session).queryingCustomerOrders();
        verify(session).result(any(ToolUiResult.class));
        verify(session).delta("已为你查询客户 C001 的订单，订单卡片已展示。");
        verify(contextService, never()).prepare(any(), any(), any());
        verifyNoInteractions(aiChatService);
    }
}
