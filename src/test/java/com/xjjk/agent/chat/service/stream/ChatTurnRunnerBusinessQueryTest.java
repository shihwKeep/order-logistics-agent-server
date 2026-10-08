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
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent;
import com.xjjk.agent.chat.orchestration.CompositeQueryPlan;
import com.xjjk.agent.chat.orchestration.CompositeQueryService;
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
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.service.ProductSearchGateway;
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
import java.util.Map;
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
    private ProductSearchGateway productSearchGateway;
    @Mock
    private BusinessQueryPlanner planner;
    @Mock
    private CompositeQueryService compositeQueryService;
    @Mock
    private DeterministicUserMemoryAnswerService directMemoryService;
    @Mock
    private ChatSseSession sessionOne;
    @Mock
    private ChatSseSession sessionTwo;

    @Test
    void explicitSkuQueryUsesDeterministicProductActionWithoutModel() throws Exception {
        String message = "请查询商品 SKU 1060904801 的详情。";
        ChatTurnContext turn = turn("request-product", "user-product", "assistant-product");
        when(preparationService.prepare(null, IDENTITY, message)).thenReturn(turn);
        when(planner.plan(message)).thenReturn(BusinessQueryPlan.direct(
                new ChatActionRequest(
                        "QUERY_PRODUCT", null, null, null, "1060904801"),
                "product-list"));
        ProductSearchResult result = new ProductSearchResult(
                "1060904801", 1, 10, 0, false, List.of());
        when(productSearchGateway.search(ProductSearchQuery.of(
                "1060904801", 1, 10))).thenReturn(result);
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending("product-list"));

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-product");

        verify(productSearchGateway).search(ProductSearchQuery.of(
                "1060904801", 1, 10));
        verify(sessionOne).result(any(ToolUiResult.class));
        verify(sessionOne).delta("未查询到商品标识 1060904801 对应的商品信息。");
        verifyNoInteractions(contextService, aiChatService, compositeQueryService);
    }

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
        when(aiChatService.stream(eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class)))
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
    void missingCompositeIdentifierIsClarifiedWithoutCallingModelOrKnowledge()
            throws Exception {
        String message = "请查询订单【完整订单号】当前物流状态，并根据物流停滞规则判断客服应该如何处理";
        ChatTurnContext turn = turn("request-clarification", "user-clarification",
                "assistant-clarification");
        when(preparationService.prepare(null, IDENTITY, message)).thenReturn(turn);
        BusinessQueryPlan plan = BusinessQueryPlan.clarification(
                "请提供完整订单号或运单号后，我才能查询当前物流状态并结合规则判断。");
        when(planner.plan(message)).thenReturn(plan);

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-clarification");

        verify(sessionOne).delta(plan.clarificationMessage());
        verifyNoInteractions(contextService, aiChatService, compositeQueryService);
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
        when(aiChatService.stream(eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class)))
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
    void compositeQueryPublishesVerifiedResultsAndStreamsGroundedAnswer() throws Exception {
        String compositeMessage = "查询订单 XJ202609290001 的物流，并根据物流规则判断是否需要预警";
        ChatTurnContext turn = turn("request-composite", "user-composite", "assistant-composite");
        when(preparationService.prepare(null, IDENTITY, compositeMessage)).thenReturn(turn);
        BusinessQueryPlan plan = BusinessQueryPlan.composite(CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge(compositeMessage))));
        when(planner.plan(compositeMessage)).thenReturn(plan);
        when(contextService.prepare(eq(turn), eq(compositeMessage), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));

        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-09T13:41:38+08:00");
        ToolUiResult logistics = new ToolUiResult(
                "get_order_logistics", "logistics-timeline", 1, queriedAt,
                Map.of("status", "运输中"));
        KnowledgeRetrievalResult evidence = new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                        2L, 3L, 4L, "chunk-1", "物流制度", "第七章",
                        "干线停滞超过阈值生成预警。", "{}", 0.91D, Set.of("KEYWORD"))),
                "hybrid-v1", "NONE", "OK", queriedAt);
        ToolUiResult knowledge = new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1, queriedAt, evidence);
        when(compositeQueryService.execute(
                eq(plan.compositePlan()), eq(compositeMessage), eq(IDENTITY), anyString()))
                .thenReturn(new CompositeQueryService.CompositeQueryResult(
                        true, "SUCCESS", List.of(logistics, knowledge, logistics),
                        Set.of("logistics-timeline", "knowledge-citations"),
                        "业务事实：运输中\n企业知识依据：干线停滞超过阈值生成预警。", ""));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending(1, "logistics-timeline"));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(2)))
                .thenReturn(pending(2, "knowledge-citations"));
        when(aiChatService.streamGroundedComposite(
                eq(compositeMessage), any(), anyString()))
                .thenReturn(Flux.just(response("建议核对当前节点后再决定是否预警")));

        ChatTurnRunner runner = runner();
        runner.run(new ChatStreamRequest(null, compositeMessage, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-composite");

        verify(compositeQueryService).execute(
                eq(plan.compositePlan()), eq(compositeMessage), eq(IDENTITY), anyString());
        verify(aiChatService).streamGroundedComposite(
                eq(compositeMessage), any(), anyString());
        verify(sessionOne, times(2)).result(any(ToolUiResult.class));
        verify(sessionOne).delta("建议核对当前节点后再决定是否预警");
        verify(aiChatService, never()).stream(
                anyString(), any(), any(), any(BusinessQueryPlan.class));
    }

    @Test
    void compositePartialSuccessPublishesCompletedCardsAndSafeMissingBranchMessage()
            throws Exception {
        String compositeMessage =
                "查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断";
        ChatTurnContext turn = turn("request-partial", "user-partial", "assistant-partial");
        when(preparationService.prepare(null, IDENTITY, compositeMessage)).thenReturn(turn);
        BusinessQueryPlan plan = BusinessQueryPlan.composite(CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge(compositeMessage))));
        when(planner.plan(compositeMessage)).thenReturn(plan);
        when(compositeQueryService.execute(
                eq(plan.compositePlan()), eq(compositeMessage), eq(IDENTITY), anyString()))
                .thenReturn(new CompositeQueryService.CompositeQueryResult(
                        false, "PARTIAL_SUCCESS",
                        List.of(new ToolUiResult(
                                "list_customer_orders", "order-list", 1,
                                OffsetDateTime.parse("2026-09-09T13:41:38+08:00"),
                                Map.of("count", 1))),
                        Set.of("order-list", "knowledge-citations"),
                        "业务事实：客户订单数量=1\n",
                        "部分实时业务查询已完成；物流查询未完成，无法确认物流状态。"));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending(1, "order-list"));

        runner().run(new ChatStreamRequest(null, compositeMessage, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-partial");

        verify(sessionOne).result(any(ToolUiResult.class));
        verify(sessionOne).delta(
                "部分实时业务查询已完成；物流查询未完成，无法确认物流状态。");
        verifyNoInteractions(contextService, aiChatService);
    }

    @Test
    void emptyProductCompositeReturnsDeterministicNoMatchWithoutModelAnswer() throws Exception {
        String compositeMessage =
                "请查询不存在的 SKU TEST_SKU_000000 的商品详情，并结合企业定价规则判断当前价格是否存在可核验的计算异常";
        ChatTurnContext turn = turn("request-empty-product", "user-empty-product",
                "assistant-empty-product");
        when(preparationService.prepare(null, IDENTITY, compositeMessage)).thenReturn(turn);
        BusinessQueryPlan plan = BusinessQueryPlan.composite(CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.product("TEST_SKU_000000"),
                CompositeQueryIntent.knowledge(compositeMessage))));
        when(planner.plan(compositeMessage)).thenReturn(plan);
        ProductSearchResult empty = new ProductSearchResult(
                "TEST_SKU_000000", 1, 10, 0, false, List.of());
        ToolUiResult product = new ToolUiResult(
                "search_products", "product-list", 1,
                OffsetDateTime.parse("2026-09-09T13:41:38+08:00"), empty);
        when(compositeQueryService.execute(
                eq(plan.compositePlan()), eq(compositeMessage), eq(IDENTITY), anyString()))
                .thenReturn(new CompositeQueryService.CompositeQueryResult(
                        true, "SUCCESS", List.of(product), Set.of("product-list"),
                        "业务事实：\nproduct-list：商品数量=0", ""));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending(1, "product-list"));
        runner().run(new ChatStreamRequest(null, compositeMessage, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-empty-product");

        verify(sessionOne).result(product);
        verify(sessionOne).delta("未查询到 SKU 为 TEST_SKU_000000 的商品信息，该 SKU 在系统中不存在。");
        verify(aiChatService, never()).streamGroundedComposite(
                anyString(), any(), anyString());
    }

    @Test
    void compositeAnswerIsCorrectedBeforePublicationWhenItClaimsUnverifiedExecution()
            throws Exception {
        String compositeMessage = "查询订单 XJ202609290001 的物流，并根据物流规则判断是否需要预警";
        stubCompositeQuery(compositeMessage);
        when(aiChatService.streamGroundedComposite(
                eq(compositeMessage), any(), anyString()))
                .thenReturn(Flux.just(response("系统已生成预警。")));
        when(aiChatService.streamGroundedCompositeCorrection(
                eq(compositeMessage), any(), anyString(), eq("系统已生成预警。"), any()))
                .thenReturn(Flux.just(response("客服应生成预警并联系承运商核查。")));

        runner().run(new ChatStreamRequest(null, compositeMessage, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-composite-corrected");

        verify(aiChatService).streamGroundedComposite(
                eq(compositeMessage), any(), anyString());
        verify(aiChatService).streamGroundedCompositeCorrection(
                eq(compositeMessage), any(), anyString(), eq("系统已生成预警。"), any());
        verify(sessionOne).delta("客服应生成预警并联系承运商核查。");
        verify(sessionOne, never()).delta("系统已生成预警。");
    }

    @Test
    void compositeAnswerFallsBackWhenCorrectionStillViolatesPolicy() throws Exception {
        String compositeMessage = "查询订单 XJ202609290001 的物流，并根据物流规则判断是否需要预警";
        stubCompositeQuery(compositeMessage);
        when(aiChatService.streamGroundedComposite(
                eq(compositeMessage), any(), anyString()))
                .thenReturn(Flux.just(response("距最新轨迹已超24小时无有效更新。")));
        when(aiChatService.streamGroundedCompositeCorrection(
                eq(compositeMessage), any(), anyString(),
                eq("距最新轨迹已超24小时无有效更新。"), any()))
                .thenReturn(Flux.just(response("正在核实中。")));
        when(aiChatService.compositeSafeFallback(anyString()))
                .thenReturn("已达到当前环节阈值，客服应按规则处理；动作执行结果以业务系统返回为准。");

        runner().run(new ChatStreamRequest(null, compositeMessage, null), IDENTITY,
                new ChatStreamControl(), sessionOne, "fallback-composite-fallback");

        verify(aiChatService).streamGroundedCompositeCorrection(
                eq(compositeMessage), any(), anyString(),
                eq("距最新轨迹已超24小时无有效更新。"), any());
        verify(sessionOne).delta(
                "已达到当前环节阈值，客服应按规则处理；动作执行结果以业务系统返回为准。");
        verify(sessionOne, never()).delta("距最新轨迹已超24小时无有效更新。");
        verify(sessionOne, never()).delta("正在核实中。");
    }

    @Test
    void retriesTransientModelStreamFailureBeforePublishingText() throws Exception {
        ChatTurnContext turn = turn("request-5", "user-5", "assistant-5");
        when(preparationService.prepare(null, IDENTITY, MESSAGE)).thenReturn(turn);
        when(planner.plan(MESSAGE)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        when(contextService.prepare(eq(turn), eq(MESSAGE), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        when(aiChatService.stream(eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class)))
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

        verify(aiChatService, times(2)).stream(
                eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class));
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
        when(aiChatService.stream(eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class)))
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
        verify(aiChatService).stream(
                eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class));
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
        when(aiChatService.stream(eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class)))
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
        verify(aiChatService).stream(
                eq(MESSAGE), any(), any(), any(BusinessQueryPlan.class));
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
                afterSaleAvailability,
                productSearchGateway);
        ChatTurnRunner runner = new ChatTurnRunner(
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
        runner.setCompositeQueryService(compositeQueryService);
        return runner;
    }

    private void stubCompositeQuery(String compositeMessage) {
        ChatTurnContext turn = turn(
                "request-composite-policy", "user-composite-policy", "assistant-composite-policy");
        when(preparationService.prepare(null, IDENTITY, compositeMessage)).thenReturn(turn);
        BusinessQueryPlan plan = BusinessQueryPlan.composite(CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge(compositeMessage))));
        when(planner.plan(compositeMessage)).thenReturn(plan);
        when(contextService.prepare(eq(turn), eq(compositeMessage), any(ChatStreamControl.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatContextSelection.class));
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-09T13:41:38+08:00");
        ToolUiResult logistics = new ToolUiResult(
                "get_order_logistics", "logistics-timeline", 1, queriedAt,
                Map.of("status", "运输中"));
        KnowledgeRetrievalResult evidence = new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                        2L, 3L, 4L, "chunk-1", "物流制度", "第七章",
                        "干线停滞超过阈值生成预警。", "{}", 0.91D, Set.of("KEYWORD"))),
                "hybrid-v1", "NONE", "OK", queriedAt);
        ToolUiResult knowledge = new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1, queriedAt, evidence);
        when(compositeQueryService.execute(
                eq(plan.compositePlan()), eq(compositeMessage), eq(IDENTITY), anyString()))
                .thenReturn(new CompositeQueryService.CompositeQueryResult(
                        true, "SUCCESS", List.of(logistics, knowledge),
                        Set.of("logistics-timeline", "knowledge-citations"),
                        "业务事实：运输中\n企业知识依据：干线停滞超过阈值生成预警。", ""));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(1)))
                .thenReturn(pending(1, "logistics-timeline"));
        when(resultRecorder.prepare(any(ToolUiResult.class), eq(2)))
                .thenReturn(pending(2, "knowledge-citations"));
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
        return pending(1, kind);
    }

    private PendingMessageResult pending(int sequence, String kind) {
        return new PendingMessageResult(
                sequence,
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
