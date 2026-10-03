# LangGraph4j 第二版复合查询编排实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or superpowers:subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有 Spring AI、SSE、权限和单一查询路径的前提下，补齐内部业务/知识/普通分析复合查询，并为 LangGraph4j 工作流增加真正并行执行、Redis checkpoint 和断点恢复。

**Architecture:** BusinessQueryPlanner 继续作为第一道安全路由；复合计划进入 CompositeQueryWorkflow。工作流将每个受控意图转换为独立 LangGraph4j 分支，独立分支并行执行，结果写入 JSON-safe 图状态后统一门禁。LangGraph4j 使用自定义 Redis BaseCheckpointSaver 保存可恢复状态；完成后的可信上下文继续交给无工具 Spring AI 生成最终回答。

**Tech Stack:** Java 21, Spring Boot, Spring AI 1.1.8, LangGraph4j core 1.8.27, Spring Data Redis StringRedisTemplate, Jackson ObjectMapper, Micrometer, JUnit 5, Mockito, Testcontainers Redis。

---

## 文件地图

- 修改 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryIntent.java`：增加必需性和依赖描述，保留三参数构造兼容现有调用方。
- 修改 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryPlan.java`：计算必需结果、外部数据标记和稳定 plan hash。
- 修改 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryState.java`：增加分支状态、结果快照、checkpoint 元数据，并确保状态可被 Jackson 序列化。
- 修改 `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`：补齐商品/客户/订单+商品/普通分析组合，识别外部数据请求但不调用外部服务。
- 修改 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`：拆分节点、并行分支、结果汇聚、恢复后的结果重建和安全终态。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryBranch.java`：定义受控分支名称、结果类型和依赖。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryBranchSnapshot.java`：保存可序列化、已脱敏的分支结果和失败摘要。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpoint.java`：定义 Redis checkpoint 的 JSON 结构。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointStore.java`：定义保存、读取、删除 checkpoint 的端口。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/RedisCompositeQueryCheckpointStore.java`：用 StringRedisTemplate 实现独立 key 前缀、TTL 和版本校验。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaver.java`：将 LangGraph4j Checkpoint 映射到 Redis checkpoint store。
- 修改 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`：接收恢复状态并返回可信结构化结果。
- 修改 `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`：保持现有 SSE 生命周期，接入复合恢复结果和无工具最终回答。
- 修改 `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`：扩展普通分析和外部数据不可用的固定提示约束。
- 修改 `src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java`：增加分支、重试和 checkpoint 指标。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryExecutorConfiguration.java`：配置有界并行执行器。
- 新增 `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointProperties.java`：绑定 `agent.chat.composite.checkpoint` 的 TTL、前缀和版本。
- 修改 `src/main/resources/application.properties`：提供本地默认 checkpoint 配置，不改变生产 Nacos 覆盖方式。
- 修改 `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`：增加第二版路由覆盖。
- 修改 `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`：增加并行、依赖、部分失败和普通分析测试。
- 新增 `src/test/java/com/xjjk/agent/chat/orchestration/RedisCompositeQueryCheckpointStoreTest.java`：覆盖 Redis 序列化、TTL、版本和异常行为。
- 新增 `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointIntegrationTest.java`：使用 Testcontainers Redis 验证中断恢复。
- 修改 `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`：验证恢复结果不重复发布。
- 修改 `src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java`：验证新增指标标签受控。

## Task 1: 扩展复合计划和可恢复状态契约

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryIntent.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryPlan.java`
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryBranch.java`
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryBranchSnapshot.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryState.java`
- Test: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryStateTest.java`

- [ ] **Step 1: 写失败测试，固定计划和状态行为**

增加以下测试：

~~~java
@Test
void intentDefaultsToRequiredWithoutDependency() {
    var intent = CompositeQueryIntent.logistics("XJ202609290001");
    assertThat(intent.required()).isTrue();
    assertThat(intent.dependsOnResultKind()).isEmpty();
}

@Test
void planExcludesOptionalExternalMarkerFromRequiredKinds() {
    var plan = CompositeQueryPlan.withExternalSource(
            List.of(CompositeQueryIntent.order("XJ202609290001"),
                    CompositeQueryIntent.general("是否合理")), true);
    assertThat(plan.requiresExternalSource()).isTrue();
    assertThat(plan.requiredResultKinds())
            .containsExactlyInAnyOrder("order-list", "general-analysis");
}

@Test
void stateStoresOnlySafeBranchSnapshots() {
    var state = CompositeQueryState.initial("req-1", "conv-1", "问题", identity(), plan());
    var updated = CompositeQueryState.withBranchSnapshot(
            state, new CompositeQueryBranchSnapshot(
                    "logistics.query", "logistics-timeline", "SUCCESS",
                    "verified summary", null, 1));
    assertThat(updated.branchSnapshots()).hasSize(1);
    assertThat(updated.toSafeLogData()).doesNotContainKey("data");
}
~~~

- [ ] **Step 2: 实现兼容的意图和分支契约**

将 `CompositeQueryIntent` 扩展为：

~~~java
public record CompositeQueryIntent(
        Source source,
        String value,
        String resultKind,
        boolean required,
        String dependsOnResultKind) implements Serializable {

    public CompositeQueryIntent(Source source, String value, String resultKind) {
        this(source, value, resultKind, true, null);
    }
}
~~~

保留现有静态工厂，并新增 optional external marker 和 general 工厂。CompositeQueryBranch 只允许固定分支名：`order.query`、`logistics.query`、`product.query`、`customer.query`、`customer-orders.query`、`after-sale.query`、`knowledge.query`、`general.analyze`、`external.unavailable`。

- [ ] **Step 3: 让图状态只使用 checkpoint-safe 值**

在 CompositeQueryState 增加：

~~~text
BRANCH_SNAPSHOTS
COMPLETED_BRANCHES
PENDING_BRANCHES
RETRY_COUNTS
PLAN_HASH
CHECKPOINT_VERSION
NEXT_NODE
~~~

CompositeQueryBranchSnapshot 只包含分支名、结果类型、状态、脱敏摘要、失败摘要和重试次数；不包含 ToolUiResult.data 原始对象、身份对象、Token 或完整异常堆栈。已有三参数意图构造和现有状态访问器继续可用。

- [ ] **Step 4: 运行状态测试确认 RED/GREEN**

先运行：

~~~powershell
mvn -Dtest=CompositeQueryStateTest test
~~~

新增断言应先因字段不存在失败，再实现最小契约后通过。随后提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/orchestration src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryStateTest.java
git commit -m "feat: define resumable composite query state"
~~~

## Task 2: 扩展 Planner 覆盖内部组合和外部数据边界

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`
- Modify: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1: 写失败路由测试**

增加这些场景：

~~~java
@Test
void productAndPolicyUsesCompositePlan() {
    var plan = planner.plan("查询鱼油商品，并根据企业定价规则判断成交价是否合理");
    assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
    assertThat(plan.acceptedResultKinds())
            .contains("product-list", "knowledge-citations", "general-analysis");
}

@Test
void customerAndAfterSalePolicyUsesCompositePlan() {
    var plan = planner.plan("查询客户 C24101816040001 的订单，并结合售后规则分析是否符合退货条件");
    assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
}

@Test
void orderProductAndMarketPriceCreatesUnsupportedExternalMarker() {
    var plan = planner.plan("查询订单 XJTS0120260820000011 的商品，并分析当前市场价格区间");
    assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
    assertThat(plan.requiresExternalSource()).isTrue();
    assertThat(plan.acceptedResultKinds()).contains("external-data-unavailable");
}

@Test
void missingOrderIdentifierStillClarifiesBeforeCompositeRouting() {
    assertThat(planner.plan("查询订单物流并根据规则判断").mode())
            .isEqualTo(BusinessQueryMode.CLARIFICATION);
}
~~~

- [ ] **Step 2: 实现稳定的组合识别**

保留现有 DIRECT 和规则-only 路径。增加以下 deterministic 规则：

~~~text
业务标识 + 规则/政策/流程/阈值 + “判断/分析/是否合理” -> BUSINESS + KNOWLEDGE + GENERAL
两个内部业务域 + “比较/结合/分析” -> 多个 BUSINESS + GENERAL
“市场价格/竞品价格/网页/外部行情” -> external-data-unavailable 标记
~~~

商品查询没有 SKU 时，使用用户消息中商品名称的安全关键词作为 ProductSearchQuery，不能把整段规则指令作为商品查询参数。缺少订单、客户或售后标识时继续返回固定澄清。

- [ ] **Step 3: 运行 Planner 和既有单一查询测试**

~~~powershell
mvn -Dtest=BusinessQueryPlannerTest,ChatTurnRunnerBusinessQueryTest test
~~~

预期：新增组合测试通过，现有 DIRECT、MODEL_REQUIRED、GENERAL 测试不变。提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
git commit -m "feat: expand composite query planning boundaries"
~~~

## Task 3: 引入 Redis checkpoint 配置和存储适配器

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpoint.java`
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointStore.java`
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/RedisCompositeQueryCheckpointStore.java`
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointProperties.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/com/xjjk/agent/chat/orchestration/RedisCompositeQueryCheckpointStoreTest.java`

- [ ] **Step 1: 写 Redis 适配器失败测试**

使用 mock StringRedisTemplate 和 ObjectMapper 验证：

~~~java
@Test
void saveUsesDedicatedPrefixAndTtl() {
    store.save(checkpoint("req-1"));
    verify(values).set(
            eq("agent:composite:checkpoint:v2:{req-1}"),
            anyString(), eq(Duration.ofMinutes(10)));
}

@Test
void loadRejectsVersionMismatch() {
    when(values.get(anyString())).thenReturn(jsonWithVersion("v1"));
    assertThat(store.load("req-1")).isEmpty();
}

@Test
void redisFailureIsReportedAndNeverPretendsRecovery() {
    when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
    assertThatThrownBy(() -> store.load("req-1"))
            .isInstanceOf(CompositeQueryCheckpointUnavailableException.class);
}
~~~

- [ ] **Step 2: 实现 checkpoint JSON 结构**

CompositeQueryCheckpoint 使用 Jackson 可序列化字段：graphVersion、threadId、requestId、conversationId、planHash、stateJson、completedNodes、pendingNodes、retryCounts、nextNode、status、updatedAt。stateJson 只允许 CompositeQueryState.toCheckpointData() 产生的 JSON-safe map。

- [ ] **Step 3: 实现 Redis store**

使用 StringRedisTemplate.opsForValue().set(key, json, ttl)，key 固定为：

~~~text
agent:composite:checkpoint:v2:{requestId}
~~~

checkpoint TTL 和版本来自 @ConfigurationProperties(prefix = "agent.chat.composite.checkpoint")。Redis 异常抛出内部 CompositeQueryCheckpointUnavailableException，调用方必须进入安全失败，不能返回“已恢复”。

- [ ] **Step 4: 运行存储测试并提交**

~~~powershell
mvn -Dtest=RedisCompositeQueryCheckpointStoreTest test
~~~

预期所有序列化、前缀、TTL、版本和异常测试通过。提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/orchestration src/main/resources/application.properties src/test/java/com/xjjk/agent/chat/orchestration/RedisCompositeQueryCheckpointStoreTest.java
git commit -m "feat: persist composite workflow checkpoints in Redis"
~~~

## Task 4: 将工作流改为真正并行分支并支持 LangGraph4j checkpoint

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryExecutorConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`
- Create: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryCheckpointIntegrationTest.java`

- [ ] **Step 1: 写并行和恢复失败测试**

在 workflow 测试中用 CountDownLatch 验证两个独立 Gateway 同时进入：

~~~java
@Test
void independentBusinessAndKnowledgeBranchesOverlap() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    when(orderGateway.search(any(), any(), any(), any())).thenAnswer(invocation -> {
        entered.countDown(); release.await(2, TimeUnit.SECONDS); return orderResult();
    });
    when(knowledgeQueryGateway.retrieve(any(), any(), any(), any())).thenAnswer(invocation -> {
        entered.countDown(); release.await(2, TimeUnit.SECONDS); return knowledgeResult();
    });
    CompletableFuture<CompositeQueryResult> future = CompletableFuture.supplyAsync(() ->
            workflow.execute(plan, "查询订单XJ202609290001并根据物流规则分析",
                    IDENTITY, "request-parallel", "conversation-1"));
    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    assertThat(future.get()).extracting(CompositeQueryResult::success).isEqualTo(true);
}
~~~

在 checkpoint integration 测试中让物流分支抛出可恢复异常，保存 checkpoint 后重新创建 workflow，验证订单 Gateway 只调用一次，并验证恢复执行只调用物流 Gateway：

~~~java
verify(orderGateway, times(1)).search(
        eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
        eq(IDENTITY), eq("request-recovery"));
verify(logisticsGateway, times(2)).logistics(
        eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
        eq(IDENTITY), eq("request-recovery"));
~~~

- [ ] **Step 2: 配置有界执行器和 LangGraph4j checkpoint saver**

创建 ThreadPoolTaskExecutor，核心线程数、最大线程数和队列固定在配置中，不能无限创建线程。图编译使用：

~~~java
CompileConfig config = CompileConfig.builder()
        .graphId("composite-v2")
        .checkpointSaver(langGraphCheckpointSaver)
        .releaseThread(false)
        .build();
CompiledGraph<CompositeQueryState> graph = stateGraph.compile(config);
RunnableConfig runConfig = RunnableConfig.builder()
        .threadId(requestId)
        .addParallelNodeExecutor("order.query", compositeExecutor)
        .addParallelNodeExecutor("logistics.query", compositeExecutor)
        .addParallelNodeExecutor("product.query", compositeExecutor)
        .addParallelNodeExecutor("customer.query", compositeExecutor)
        .addParallelNodeExecutor("knowledge.query", compositeExecutor)
        .addParallelNodeExecutor("general.analyze", compositeExecutor)
        .build();
~~~

Redis store 通过一个 BaseCheckpointSaver 适配器映射 LangGraph4j Checkpoint 与 CompositeQueryCheckpoint，图每个节点完成后自动写入；恢复使用同一个 threadId 和 RunnableConfig。

- [ ] **Step 3: 拆分分支节点和确定性汇聚**

将当前单个 business.query 拆为固定节点：

~~~text
input.validate
  -> branch.prepare
  -> order.query / logistics.query / product.query / customer.query /
     customer-orders.query / after-sale.query / knowledge.query /
     general.analyze / external.unavailable
  -> result.validate
  -> answer.compose
~~~

branch.prepare 为无依赖节点建立并行边；依赖节点只有在其 dependsOnResultKind 已成功时才进入执行。每个分支输出 CompositeQueryBranchSnapshot 和受控 ToolUiResult。同一 requestId + nodeId + intentHash 已有成功快照时直接跳过 Gateway 调用。

- [ ] **Step 4: 实现恢复后的结果重建**

checkpoint 中的已脱敏结果使用 JsonNode 保存。恢复时构造 ToolUiResult(toolName, kind, schemaVersion, queriedAt, jsonNode)，这样 SSE 回放已有卡片时不重复发布，最终回答仍可使用已保存的 answerContext。如果 checkpoint 只有状态没有安全结果载荷，则终止为 MISSING_RESULT，不能重新猜测或伪造卡片。

- [ ] **Step 5: 覆盖部分失败和依赖分支**

实现并测试：

- 非必需商品分支失败，订单+知识仍可 PARTIAL_SUCCESS；
- 必需物流分支失败，禁止进入 answer.compose；
- 订单成功后商品依赖分支才开始；
- 知识库失败返回 NO_RELIABLE_KNOWLEDGE；
- 外部数据分支只写不可用摘要，不调用任何 Gateway。

- [ ] **Step 6: 运行 workflow 和 integration 测试并提交**

~~~powershell
mvn -Dtest=CompositeQueryWorkflowTest,CompositeQueryCheckpointIntegrationTest test
~~~

预期：并行 latch、依赖顺序、部分失败、checkpoint 恢复和结果去重测试通过。提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/orchestration src/test/java/com/xjjk/agent/chat/orchestration
git commit -m "feat: parallelize and resume composite workflow"
~~~

## Task 5: 接入商品、客户、普通分析和外部数据安全提示

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/model/AiChatServiceCompositeStreamTest.java`

- [ ] **Step 1: 写结果门禁测试**

增加测试：

~~~java
@Test
void generalAnalysisReceivesOnlyVerifiedInternalContext() {
    var result = workflow.execute(orderAndAnalysisPlan(),
            "分析订单商品成交价", IDENTITY, "request-analysis", "conversation-1");
    assertThat(result.verifiedAnswerContext())
            .contains("BUSINESS_FACTS", "GENERAL_ANALYSIS_REQUEST")
            .doesNotContain("customerId", "accessToken");
}

@Test
void externalMarketPriceNeverCallsNetworkGateway() {
    var result = workflow.execute(marketPricePlan(),
            "分析当前市场价格", IDENTITY, "request-market", "conversation-1");
    assertThat(result.safeMessage()).contains("未接入外部市场或网页数据");
    verifyNoInteractions(productSearchGateway, orderGateway, knowledgeQueryGateway);
}

@Test
void customerInfoAndPolicyUsesCustomerGatewayAndKnowledgeGateway() {
    workflow.execute(customerPolicyPlan(),
            "查询客户并根据售后规则分析", IDENTITY, "request-customer", "conversation-1");
    verify(customerQueryGateway).search(
            anyString(), any(CustomerMatchType.class), eq(IDENTITY), eq("request-customer"));
    verify(knowledgeQueryGateway).retrieve(
            anyString(), anyList(), eq(IDENTITY), eq("request-customer"));
}

@Test
void missingVerifiedFactsBlocksGeneralAnalysis() {
    var result = workflow.execute(generalOnlyPlan(),
            "分析这个价格", IDENTITY, "request-missing", "conversation-1");
    assertThat(result.status()).isEqualTo("MISSING_RESULT");
    assertThat(result.verifiedAnswerContext()).isBlank();
}
~~~

测试必须验证 general.analyze 的模型调用没有任何工具 callback，并且外部市场价格场景不会调用任何商品、订单或网络客户端。

- [ ] **Step 2: 增加客户查询分支**

为 workflow 注入 CustomerQueryGateway，按 CustomerMatchType.CUSTOMER_CODE 或 CUSTOMER_NAME 查询脱敏客户结果，结果类型为 customer-list。客户订单仍继续使用 CustomerOrderQueryService，不把内部 customerId 写入状态、日志或模型上下文。

- [ ] **Step 3: 增加普通分析分支**

普通分析只在前置业务/知识分支完成后生成 general-analysis 的已验证上下文；不在 workflow 内调用模型，模型仍由 AiChatService.streamGroundedComposite 统一完成。上下文固定分区：BUSINESS_FACTS、KNOWLEDGE_EVIDENCE、GENERAL_ANALYSIS_REQUEST。

- [ ] **Step 4: 加入外部数据不可用分支**

外部请求生成安全摘要：

~~~text
当前未接入外部市场或网页数据，无法确认实时市场价格；已查询到的内部业务事实和企业规则仍可继续用于分析。
~~~

不能使用模型记忆补充市场价格，不能把外部请求降级成普通闲聊。

- [ ] **Step 5: 运行模型和 workflow 测试并提交**

~~~powershell
mvn -Dtest=CompositeQueryWorkflowTest,AiChatServiceCompositeStreamTest test
~~~

提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/orchestration src/main/java/com/xjjk/agent/chat/service/model src/test/java/com/xjjk/agent/chat/orchestration src/test/java/com/xjjk/agent/chat/service/model
git commit -m "feat: support internal analysis and external-data boundaries"
~~~

## Task 6: 接入 ChatTurnRunner，保持 SSE 和结果持久化语义

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1: 写恢复和去重失败测试**

测试要求：

~~~java
verify(session, times(1)).result(argThat(result -> result.kind().equals("order-list")));
verify(aiChatService).streamGroundedComposite(eq(message), any(), contains("BUSINESS_FACTS"));
verify(compositeQueryService).execute(any(), eq(message), eq(identity), eq(requestId));
~~~

恢复结果包含已经发布的卡片时，ChatTurnRunner 不重复向当前 SSE publisher 发送；只继续输出未完成的回答和终态。

- [ ] **Step 2: 接入复合恢复状态**

保留当前顺序：显式记忆 -> Action -> Planner。COMPOSITE 分支继续调用 CompositeQueryService，但服务返回 publishedResultSequences 和 verifiedAnswerContext，runner 只发布本轮尚未进入 SSE 的结果。失败时沿用现有安全消息，不进入模型。

- [ ] **Step 3: 保持二阶段模型无工具**

调用 streamGroundedComposite 时传入已验证上下文，不能复用带有业务 callback 的普通模型路径。现有单一 DIRECT 和 MODEL_REQUIRED 路径不得改为 LangGraph4j。

- [ ] **Step 4: 运行聊天回归测试并提交**

~~~powershell
mvn -Dtest=ChatTurnRunnerBusinessQueryTest,ChatStreamServiceReplayTest,FreshBusinessResultGateTest test
~~~

提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java src/test/java/com/xjjk/agent/chat/service/stream
git commit -m "feat: resume composite results without duplicate SSE events"
~~~

## Task 7: 增加并行、checkpoint 和部分失败观测

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java`
- Modify: `src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `docs/runbook/observability-stack.md`

- [ ] **Step 1: 写指标契约测试**

验证只出现固定低基数标签：

~~~text
agent.composite.branch{graph,branch,outcome}
agent.composite.checkpoint{graph,operation,outcome}
agent.composite.retry{graph,node,outcome}
agent.composite.node{graph,node,outcome}
agent.composite.graph{graph,outcome}
~~~

断言 orderCode、customerCode、requestId、conversationId、异常消息不会成为 Micrometer tag。

- [ ] **Step 2: 实现指标**

增加 branch()、checkpoint()、retry() 方法，固定 graph 为 composite-v2，固定 outcome 集合为 SUCCESS、FAILED、SKIPPED、PARTIAL_SUCCESS、EXPIRED、UNAVAILABLE。保留现有 composite-v1 指标兼容读取，不复用高基数业务标识。

- [ ] **Step 3: 更新 runbook 和本地配置说明**

在 application.properties 增加：

~~~properties
agent.chat.composite.checkpoint.enabled=true
agent.chat.composite.checkpoint.graph-version=v2
agent.chat.composite.checkpoint.key-prefix=agent:composite:checkpoint:v2
agent.chat.composite.checkpoint.ttl=10m
agent.chat.composite.parallel.core-pool-size=4
agent.chat.composite.parallel.max-pool-size=8
agent.chat.composite.parallel.queue-capacity=32
~~~

说明生产环境由 Nacos 覆盖，checkpoint 与 SSE 回放使用同一个 Redis 实例但不同 key 前缀和 TTL。

- [ ] **Step 4: 运行指标测试并提交**

~~~powershell
mvn -Dtest=CompositeQueryMetricsTest test
~~~

提交：

~~~powershell
git add src/main/java/com/xjjk/agent/chat/observation src/main/resources/application.properties docs/runbook/observability-stack.md src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java
git commit -m "feat: observe parallel composite workflow recovery"
~~~

## Task 8: 完整回归、Redis 集成验证和交付检查

- [ ] **Step 1: 运行定向测试**

~~~powershell
mvn -Dtest=BusinessQueryPlannerTest,CompositeQueryStateTest,CompositeQueryWorkflowTest,RedisCompositeQueryCheckpointStoreTest,CompositeQueryCheckpointIntegrationTest,ChatTurnRunnerBusinessQueryTest,CompositeQueryMetricsTest test
~~~

预期：所有定向测试通过；如果 Testcontainers Redis 不可用，必须明确报告该测试未执行，不能把单元测试通过当作集成测试通过。

- [ ] **Step 2: 运行完整 Maven 回归**

~~~powershell
mvn test
~~~

记录测试总数、失败数和跳过数。任何失败先修复代码或测试，不跳过。

- [ ] **Step 3: 检查静态和差异质量**

~~~powershell
git diff --check
git status --short
git diff --stat HEAD~8..HEAD
~~~

确认只提交第二版相关文件，保留工作区其他已有改动，不执行 reset、checkout 或清理用户文件。

- [ ] **Step 4: 验证真实链路**

至少验证四类请求：

1. 订单物流 + 物流规则；
2. 订单商品 + 企业定价规则；
3. 客户订单 + 售后规则 + 普通分析；
4. 订单商品 + 当前市场价格（看到外部数据不可用提示，且内部查询仍可展示）。

在 Trace/Grafana 中确认：并行分支、单分支耗时、checkpoint save/resume、最终门禁和下游调用共享同一 requestId/traceId。

- [ ] **Step 5: 最终状态检查**

~~~powershell
git log --oneline -10
git status --short
~~~

只在新鲜测试输出和差异检查均通过后报告完成状态。

## 计划自检

- 覆盖了 spec 中的内部业务、知识库、普通分析、外部数据边界、并行、checkpoint、恢复、幂等、失败降级、SSE 分离、观测和回归要求。
- 没有使用未完成、待定或占位描述。
- 保留了现有 CompositeQueryIntent 三参数构造、单一查询路径和 CompositeQueryService 入口语义，避免无谓破坏性迁移。
- checkpoint 只保存 JSON-safe、已脱敏结果；Redis 失败不伪造恢复成功。
- 普通分析节点不注册工具，外部请求没有网络调用。
