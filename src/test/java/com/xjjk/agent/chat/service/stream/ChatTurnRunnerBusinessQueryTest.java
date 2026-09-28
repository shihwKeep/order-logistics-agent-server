package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.tool.AfterSaleToolAvailability;
import com.xjjk.agent.chat.action.ChatActionDispatcher;
import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.routing.BusinessQueryPlan;
import com.xjjk.agent.chat.routing.BusinessQueryPlanner;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerResult;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.service.ExplicitMemoryCommandService;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ChatTurnRunnerBusinessQueryTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            10567L, "account", "name", 3673L, 1L);
    private static final String AFTER_SALE_CODE = "HH20260414_00002";
    private static final String MESSAGE = "查看售后工单 " + AFTER_SALE_CODE;

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
    private CustomerOrderQueryService customerOrderQueryService;
    @Mock
    private OrderToolAvailability orderAvailability;
    @Mock
    private AfterSaleQueryGateway afterSaleGateway;
    @Mock
    private AfterSaleToolAvailability afterSaleAvailability;
    @Mock
    private BusinessQueryPlanner planner;
    @Mock
    private DeterministicUserMemoryAnswerService directMemoryService;
    @Mock
    private ChatSseSession sessionOne;
    @Mock
    private ChatSseSession sessionTwo;

    @Test
    void repeatedManualAfterSaleQueriesEachExecuteAFreshDirectCall() throws Exception {
        ChatTurnContext first = turn("request-1", "user-1", "assistant-1");
        ChatTurnContext second = turn("request-2", "user-2", "assistant-2");
        when(preparationService.prepare(null, IDENTITY, MESSAGE))
                .thenReturn(first, second);
        when(planner.plan(MESSAGE)).thenReturn(BusinessQueryPlan.direct(
                new ChatActionRequest(
                        "QUERY_AFTER_SALE_DETAIL", null, null, AFTER_SALE_CODE),
                "after-sale-detail"));
        when(afterSaleAvailability.isDetailAvailable(IDENTITY)).thenReturn(true);
        when(afterSaleGateway.detail(eq(AFTER_SALE_CODE), eq(IDENTITY), anyString()))
                .thenReturn(detail());
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending("after-sale-detail"));

        ChatTurnRunner runner = runner();
        ChatStreamRequest request = new ChatStreamRequest(null, MESSAGE, null);
        runner.run(request, IDENTITY, new ChatStreamControl(), sessionOne, "fallback-1");
        runner.run(request, IDENTITY, new ChatStreamControl(), sessionTwo, "fallback-2");

        verify(afterSaleGateway, times(2)).detail(
                eq(AFTER_SALE_CODE), eq(IDENTITY), anyString());
        verify(sessionOne).result(any(ToolUiResult.class));
        verify(sessionTwo).result(any(ToolUiResult.class));
        verifyNoInteractions(contextService, aiChatService, directMemoryService);
    }

    @Test
    void modelBusinessQueryWithoutCurrentTurnResultCannotPublishSuccessClaim()
            throws Exception {
        String hallucinated = "已查询，卡片已展示";
        ChatTurnContext turn = turn("request-3", "user-3", "assistant-3");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any()))
                .thenReturn(Flux.just(response(hallucinated)));

        ChatTurnRunner runner = runner();
        runner.run(
                new ChatStreamRequest(null, MESSAGE, null),
                IDENTITY,
                new ChatStreamControl(),
                sessionOne,
                "fallback-3");

        verify(sessionOne, never()).delta(hallucinated);
        verify(sessionOne).delta(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        verify(sessionOne, never()).result(any());
    }

    @Test
    void outputLimitCannotPersistUnverifiedBufferedClaim() throws Exception {
        String hallucinated = "订单已经发货";
        ChatTurnContext turn = turn("request-4", "user-4", "assistant-4");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("order-list")));
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any()))
                .thenReturn(Flux.just(response(hallucinated, "length")));

        ChatStreamControl control = new ChatStreamControl();
        runner().run(new ChatStreamRequest(null, MESSAGE, null),
                IDENTITY, control, sessionOne, "fallback-4");

        ArgumentCaptor<ChatTurnExecution> executionCaptor =
                ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(executionCaptor.capture(), eq(control), eq(sessionOne));
        assertThat(executionCaptor.getValue().content.toString())
                .isEqualTo(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        assertThat(executionCaptor.getValue().resultSnapshot()).isEmpty();
        verify(sessionOne, never()).delta(hallucinated);
    }

    @Test
    void retriesTransientModelStreamFailureBeforePublishingText() throws Exception {
        ChatTurnContext turn = turn("request-5", "user-5", "assistant-5");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any()))
                .thenReturn(
                        Flux.error(new RuntimeException(
                                new IOException("connection reset"))),
                        Flux.just(response("重试后的完整回答")));

        ChatTurnRunner runner = runner();
        runner.setModelRetryProperties(new ChatStreamProperties.ModelRetry(
                3, Duration.ZERO, Duration.ZERO, 0.0));
        runner.run(
                new ChatStreamRequest(null, MESSAGE, null),
                IDENTITY,
                new ChatStreamControl(),
                sessionOne,
                "fallback-5");

        verify(aiChatService, times(2)).stream(eq(MESSAGE), any(), any());
        verify(sessionOne).delta(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
    }

    @Test
    void reportsRetryExhaustedWhenTransientModelFailureCannotRecover() throws Exception {
        ChatTurnContext turn = turn("request-6", "user-6", "assistant-6");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any()))
                .thenReturn(Flux.error(new RuntimeException(
                        new IOException("connection reset"))));

        ChatTurnRunner runner = runner();
        runner.setModelRetryProperties(new ChatStreamProperties.ModelRetry(
                1, Duration.ZERO, Duration.ZERO, 0.0));
        ChatStreamControl control = new ChatStreamControl();
        runner.run(
                new ChatStreamRequest(null, MESSAGE, null),
                IDENTITY,
                control,
                sessionOne,
                "fallback-6");

        ArgumentCaptor<ChatTurnExecution> executionCaptor =
                ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(executionCaptor.capture(), eq(control), eq(sessionOne));
        assertThat(executionCaptor.getValue().error.code())
                .isEqualTo("MODEL_STREAM_RETRY_EXHAUSTED");
        verify(aiChatService).stream(eq(MESSAGE), any(), any());
    }

    @Test
    void doesNotRetryAfterOrdinaryAnswerTextWasSent() throws Exception {
        ChatTurnContext turn = turn("request-7", "user-7", "assistant-7");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(BusinessQueryPlan.general());
        when(directMemoryService.answer(eq(IDENTITY), eq(MESSAGE), anyString()))
                .thenReturn(DeterministicUserMemoryAnswerResult.notHandled());
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(response("已经发送的片段", "stop")),
                        Mono.delay(Duration.ofMillis(20))
                                .thenMany(Flux.error(new RuntimeException(
                                        new IOException("connection reset"))))));

        ChatTurnRunner runner = runner();
        runner.setModelRetryProperties(new ChatStreamProperties.ModelRetry(
                3, Duration.ZERO, Duration.ZERO, 0.0));
        runner.run(
                new ChatStreamRequest(null, MESSAGE, null),
                IDENTITY,
                new ChatStreamControl(),
                sessionOne,
                "fallback-7");

        ArgumentCaptor<ChatTurnExecution> ordinaryExecutionCaptor =
                ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(
                ordinaryExecutionCaptor.capture(), any(ChatStreamControl.class), eq(sessionOne));
        assertThat(ordinaryExecutionCaptor.getValue().queryPlan.mode())
                .isEqualTo(com.xjjk.agent.chat.routing.BusinessQueryMode.GENERAL);
        assertThat(ordinaryExecutionCaptor.getValue().isModelTextSent()).isTrue();
        verify(aiChatService).stream(eq(MESSAGE), any(), any());
        verify(sessionOne).delta("已经发送的片段");
    }

    private ChatTurnRunner runner() {
        ExplicitMemoryCommandService memoryService =
                org.mockito.Mockito.mock(ExplicitMemoryCommandService.class);
        when(memoryService.handle(any(), anyString()))
                .thenReturn(ExplicitMemoryCommandResult.notHandled());
        ChatActionDispatcher dispatcher = new ChatActionDispatcher(
                orderGateway,
                customerOrderQueryService,
                orderAvailability,
                afterSaleGateway,
                afterSaleAvailability);
        return new ChatTurnRunner(
                preparationService,
                contextService,
                aiChatService,
                finalizer,
                resultRecorder,
                dispatcher,
                planner,
                new FreshBusinessResultGate(),
                memoryService,
                directMemoryService);
    }

    private ChatTurnContext turn(
            String requestId, String userMessageId, String assistantMessageId) {
        return new ChatTurnContext(
                1L,
                10567L,
                "conversation-1",
                requestId,
                userMessageId,
                assistantMessageId,
                "prompt-v1");
    }

    private AfterSaleDetailResult detail() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T13:41:38+08:00");
        return new AfterSaleDetailResult(
                AFTER_SALE_CODE,
                90,
                "完结",
                now,
                true,
                now,
                "百*",
                "K15102200044",
                "XJTS0120260414000005",
                "HTTS0120260414000001",
                null,
                null,
                List.of(),
                List.of(),
                new AfterSaleDetailResult.RefundSummary(0L, 0L, 0L, 0L, null),
                false,
                false,
                now);
    }

    private PendingMessageResult pending(String kind) {
        return new PendingMessageResult(
                1,
                "get_after_sale_detail",
                kind,
                1,
                "{\"afterSaleCode\":\"HH20260414_00002\"}",
                48,
                OffsetDateTime.parse("2026-09-09T13:41:38+08:00"));
    }

    private ChatResponse response(String text) {
        return response(text, "stop");
    }

    private ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }
}
