# Order and Logistics Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为享佳智能坐席助手增加生产可用的订单精确查询、物流轨迹查询、结构化卡片持久化和会话内订单引用能力。

**Architecture:** Agent 只调用 `order` 的专用只读接口；`order` 负责可信身份下的订单权限、订单/商品/运单聚合，并在需要完整轨迹时调用 `silu-logistics` 的内部批量接口。模型只接收有界文本，完整脱敏结果通过 SSE 发给 Electron，并与助手消息在同一事务中持久化。

**Tech Stack:** Java 11、Spring Boot 2.4、Spring Cloud OpenFeign、MyBatis、Elasticsearch、Java 21、Spring Boot 3.5、Spring AI 1.1.8、MyBatis-Plus、MySQL、SSE、Electron、Vue 3、Pinia、Vitest。

---

### Task 1: 把商品专用 ToolContext 重构为通用请求上下文

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/tool/AgentToolRequestContext.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/tool/ToolUiResult.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/tool/ToolOutputPublisher.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/tool/ToolCallGuard.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/tool/ToolCallLimitExceededException.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/product/tool/ProductQueryTools.java`
- Delete: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/product/tool/ProductToolRequestContext.java`
- Delete: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/product/tool/ProductResultPublisher.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/tool/ToolCallGuardTest.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/product/tool/ProductQueryToolsTest.java`

- [ ] **Step 1: 写调用上限和同参去重失败测试**

```java
@Test
void executesSameCanonicalCallOnceAndLimitsDistinctCalls() {
    ToolCallGuard guard = new ToolCallGuard(3);
    AtomicInteger executions = new AtomicInteger();

    String first = guard.execute("search_orders", "AUTO|O1", () -> {
        executions.incrementAndGet();
        return "result-1";
    });
    String duplicate = guard.execute("search_orders", "AUTO|O1", () -> {
        executions.incrementAndGet();
        return "unexpected";
    });
    guard.execute("search_orders", "AUTO|O2", () -> "result-2");
    guard.execute("get_order_logistics", "ORDER_CODE|O1", () -> "result-3");

    assertThat(first).isEqualTo("result-1");
    assertThat(duplicate).isEqualTo("result-1");
    assertThat(executions).hasValue(1);
    assertThatThrownBy(() -> guard.execute("search_products", "fish|1", () -> "fourth"))
            .isInstanceOf(ToolCallLimitExceededException.class);
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=ToolCallGuardTest,ProductQueryToolsTest test`

Expected: FAIL。

- [ ] **Step 3: 创建通用上下文**

```java
public record AgentToolRequestContext(
        String requestId,
        AgentIdentity identity,
        ToolOutputPublisher outputPublisher,
        ToolCallGuard callGuard) {
    public static final String CONTEXT_KEY = "agentToolRequestContext";
}

public record ToolUiResult(
        String toolName,
        String kind,
        int schemaVersion,
        OffsetDateTime queriedAt,
        Object data) {
}

@FunctionalInterface
public interface ToolOutputPublisher {
    void publish(ToolUiResult result);
}
```

`ToolCallGuard` 使用 `ConcurrentHashMap<String, CompletableFuture<String>>` 保存首次调用结果，以 `toolName + '\n' + canonicalArguments` 为键；先增加不同键数量，超过 3 立即抛出上限异常。首次执行异常时从 Map 删除该键，允许调用方按明确重试规则再尝试；成功结果不重复执行也不重复发布 SSE。

- [ ] **Step 4: 迁移商品工具和模型入口**

`ProductQueryTools` 从通用上下文读取身份、发布器和 Guard；规范键为 `normalizedKeyword + "|" + pageIndex + "|10"`。`AiChatService.stream` 参数改为 `AgentToolRequestContext`，`ToolContext` 只使用新键。`ChatTurnRunner` 每轮创建一个 Guard，不能把 Guard 注册为 Spring 单例。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=ToolCallGuardTest,ProductQueryToolsTest test`

Expected: PASS。

- [ ] **Step 6: 提交通用工具上下文**

```bash
git add src/main/java/com/xjjk/agent/tool src/main/java/com/xjjk/agent/product/tool src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/test/java/com/xjjk/agent/tool src/test/java/com/xjjk/agent/product/tool/ProductQueryToolsTest.java
git commit -m "refactor: introduce generic agent tool context"
```

### Task 2: Agent 订单/物流 Feign 适配器与领域模型

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderIdentifierType.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderSearchResult.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderCard.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderGoodsSummary.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderLogisticsResult.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/ShipmentTimeline.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/TrackNode.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderSearchClient.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderLogisticsClient.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderFeignConfiguration.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderServiceResponse.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderServiceGateway.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/service/OrderQueryGateway.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/service/OrderServiceUnavailableException.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/client/OrderServiceGatewayTest.java`

- [ ] **Step 1: 写可信身份头与映射失败测试**

```java
@Test
void sendsServerIdentityHeadersAndMapsOrderResponse() {
    AtomicReference<Headers> captured = new AtomicReference<>();
    OrderSearchClient client = (token, tenant, user, org, requestId, body) -> {
        captured.set(new Headers(token, tenant, user, org, requestId));
        return success(orderSearchData("O123"));
    };
    OrderServiceGateway gateway = gateway(client, unusedLogisticsClient(), "service-secret");

    OrderSearchResult result = gateway.search(
            "O123", OrderIdentifierType.AUTO, identity(), "request-1");

    assertThat(captured.get()).isEqualTo(
            new Headers("service-secret", 1L, 10567L, 10L, "request-1"));
    assertThat(result.items()).singleElement()
            .extracting(OrderCard::orderCode).isEqualTo("O123");
}
```

同时覆盖：`Code/code`、`Data/data` 两种包装字段；空响应、非成功码和空 data 转为安全异常；金额不从分转换为字符串；响应不存在内部 `orderId`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=OrderServiceGatewayTest test`

Expected: FAIL。

- [ ] **Step 3: 创建两个独立 Feign 客户端**

```java
@FeignClient(name = "order-agent-search", url = "${integration.order.base-url}",
        configuration = OrderFeignConfiguration.class)
public interface OrderSearchClient {
    @PostMapping("/internal/agent/orders/search")
    OrderServiceResponse<OrderSearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody OrderSearchRequest request);
}
```

`OrderLogisticsClient` 使用独立 client name `order-agent-logistics` 和 `/internal/agent/orders/logistics`，参数头完全一致。两个客户端便于配置不同读取超时和熔断器。Feign `Retryer` 设为 `NEVER_RETRY`，一次显式瞬时故障重试放在 Gateway 中并受测试约束。

- [ ] **Step 4: 实现 Gateway**

Gateway 从 `AgentIdentity` 生成全部身份头；调用者不能传 tenant/user/org。只对 Feign 的连接异常、读取超时或 502/503/504 重试一次，其他异常直接映射 `OrderServiceUnavailableException`。日志只记录 requestId、工具名、匹配类型、数量和耗时。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=OrderServiceGatewayTest test`

Expected: PASS。

- [ ] **Step 6: 提交 Agent 下游适配器**

```bash
git add src/main/java/com/xjjk/agent/order/domain src/main/java/com/xjjk/agent/order/client src/main/java/com/xjjk/agent/order/service src/test/java/com/xjjk/agent/order/client
git commit -m "feat: add authenticated order service gateway"
```

### Task 3: Spring AI 订单和物流工具

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/tool/OrderQueryTools.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/tool/OrderToolAvailability.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/tool/OrderQueryToolsTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/tool/OrderToolAvailabilityTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamBusinessResultContractTest.java`

- [ ] **Step 1: 写双输出失败测试**

```java
@Test
void publishesFullOrderCardsButReturnsBoundedModelText() {
    AtomicReference<ToolUiResult> published = new AtomicReference<>();
    OrderQueryTools tools = toolsReturning(orderSearchResultWithFiveCards());
    AgentToolRequestContext context = context(published::set);

    String text = tools.searchOrders("O123", "AUTO", toolContext(context));

    assertThat(published.get().kind()).isEqualTo("order-list");
    assertThat(published.get().data()).isInstanceOf(OrderSearchResult.class);
    assertThat(text).contains("查询时间", "O123", "前端已展示");
    assertThat(text.length()).isLessThan(2000);
    assertThat(text).doesNotContain("payload_json", "receiverTelephoneId");
}
```

物流工具测试断言完整 50 条轨迹只存在于发布结果中，模型文本只包含订单号、运单号、最新状态、最新一条轨迹和查询时间。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=OrderQueryToolsTest,OrderToolAvailabilityTest,ChatStreamBusinessResultContractTest test`

Expected: FAIL。

- [ ] **Step 3: 实现两个工具**

```java
@Tool(name = "search_orders", description = "按完整订单号、外部订单号或运单号精确查询当前坐席有权查看的订单。用户询问具体订单信息时必须调用。")
public String searchOrders(
        @ToolParam(description = "完整订单号、外部订单号或运单号") String identifier,
        @ToolParam(description = "AUTO、ORDER_CODE、OUTER_ORDER_CODE或LOGISTICS_CODE", required = false) String identifierType,
        ToolContext toolContext) {
    AgentToolRequestContext context = requireContext(toolContext);
    OrderIdentifierType type = OrderIdentifierType.parseOrAuto(identifierType);
    String canonical = type.name() + "|" + normalize(identifier);
    return context.callGuard().execute("search_orders", canonical, () ->
            executeSearch(identifier, type, context));
}
```

`get_order_logistics` 使用相同规范化方式和 Guard。发布结果固定为：订单 `toolName=search_orders/kind=order-list/schemaVersion=1`；物流 `toolName=get_order_logistics/kind=logistics-timeline/schemaVersion=1`。

- [ ] **Step 4: 扩展 SSE 结果元数据并注册工具**

```java
public record Result(
        String kind,
        int schemaVersion,
        OffsetDateTime queriedAt,
        Object data) {
}
```

`AiChatService` 始终保留现有 `productQueryTools`；订单/物流工具由 `OrderToolAvailability` 根据主开关、`rollout-mode=OFF|ALLOWLIST|ALL` 和当前 `AgentIdentity.orgId` 在每次请求构造 ToolCallbacks 时决定是否注册。主开关关闭、模式为 `OFF`，或 `ALLOWLIST` 下当前组织不在列表时，模型上下文中完全不存在对应工具，不能只让工具内部返回禁用文本。空白/未知模式必须按 `OFF` 处理。订单与物流使用独立判断；工具说明和模型压缩文本纳入现有 Token 预算的工具保留量。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=OrderQueryToolsTest,OrderToolAvailabilityTest,ChatStreamBusinessResultContractTest test`

Expected: PASS。

- [ ] **Step 6: 提交工具**

```bash
git add src/main/java/com/xjjk/agent/order/tool src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java src/test/java/com/xjjk/agent/order/tool src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamBusinessResultContractTest.java
git commit -m "feat: add order and logistics AI tools"
```

### Task 4: 创建结构化结果表和持久化模型

**Files:**
- User-create: `D:/GitCode/order-logistics-agent-server/src/main/resources/db/migration/V8__create_agent_message_result.sql`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/result/AgentMessageResultEntity.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/result/AgentMessageResultMapper.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/result/PendingMessageResult.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/result/ChatToolResultRecorder.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/config/ChatResultProperties.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/result/ChatToolResultRecorderTest.java`

- [ ] **Step 1: 由用户创建 V8 迁移**

```sql
CREATE TABLE agent_message_result (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    user_id BIGINT NOT NULL COMMENT '坐席用户ID',
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会话ID',
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本轮请求ID',
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '助手消息ID',
    result_sequence INT NOT NULL COMMENT '同一回答内结构化结果序号',
    tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '工具名称',
    kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果类型',
    schema_version INT NOT NULL COMMENT '结构化结果版本',
    payload_json MEDIUMTEXT NOT NULL COMMENT '已裁剪脱敏的JSON快照',
    queried_at DATETIME(3) NOT NULL COMMENT '业务数据查询时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_result_request_sequence (request_id, result_sequence),
    KEY idx_message_result_owner_message (tenant_id, user_id, conversation_id, message_id),
    CONSTRAINT fk_message_result_conversation FOREIGN KEY (conversation_id)
        REFERENCES agent_conversation (conversation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_message_result_message FOREIGN KEY (message_id)
        REFERENCES agent_message (message_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent助手消息结构化结果快照';
```

- [ ] **Step 2: 启动 Agent 并由用户验证 Flyway V8 成功**

Run: `.\mvnw.cmd spring-boot:run`

Expected: `flyway_schema_history` 存在 `version=8, success=1`，表和两个外键存在。

- [ ] **Step 3: 写序列化上限失败测试**

```java
@Test
void rejectsOversizedResultBeforeSsePublication() {
    ChatResultProperties properties = new ChatResultProperties(true, 1024);
    ChatToolResultRecorder recorder = new ChatToolResultRecorder(objectMapper, properties);
    ToolUiResult result = new ToolUiResult(
            "get_order_logistics", "logistics-timeline", 1,
            OffsetDateTime.now(), Map.of("trace", "x".repeat(2048)));

    assertThatThrownBy(() -> recorder.prepare(result, 1))
            .isInstanceOf(ToolResultTooLargeException.class);
}
```

- [ ] **Step 4: 实现发布前记录器**

`prepare` 在发送 SSE 之前完成：字段校验 → Jackson 序列化 → UTF-8 字节数校验 → 生成不可变 `PendingMessageResult`。序号从 1 递增，`toString()` 不输出 `payloadJson`。超大结果由上层转换为有界的降级结果，不能先发送再发现无法保存。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=ChatToolResultRecorderTest test`

Expected: PASS。

- [ ] **Step 6: 提交迁移和持久化模型**

```bash
git add src/main/resources/db/migration/V8__create_agent_message_result.sql src/main/java/com/xjjk/agent/chat/result src/main/java/com/xjjk/agent/chat/config/ChatResultProperties.java src/test/java/com/xjjk/agent/chat/result
git commit -m "feat: add structured message result storage"
```

### Task 5: 将结构化结果纳入聊天收尾事务和历史接口

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnFinalizer.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatMessageResultResponse.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatMessageResponse.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/conversation/AgentMessageQueryService.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/service/turn/ChatTurnStructuredResultFinishTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/service/conversation/AgentMessageResultQueryServiceTest.java`

- [ ] **Step 1: 写收尾原子性失败测试**

```java
@Test
void savesAssistantTerminalStateAndResultsInOneTransaction() {
    PendingMessageResult result = pending("search_orders", "order-list", "{\"items\":[]}");

    assertThat(service.finish(turn, MessageStatus.SUCCESS, "已查询", "STOP", null,
            List.of(result))).isTrue();

    assertThat(messageMapper.selectByMessageId(turn.assistantMessageId()).getStatus())
            .isEqualTo("SUCCESS");
    assertThat(resultMapper.selectByMessageIds(List.of(turn.assistantMessageId())))
            .singleElement().extracting(AgentMessageResultEntity::getKind)
            .isEqualTo("order-list");
}
```

增加回滚测试：结果批量插入失败时，助手消息仍为 `GENERATING`、会话租约仍存在、稳定历史游标不推进。增加取消/模型失败测试：已经生成的结果仍与终态助手消息一并保存。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=ChatTurnStructuredResultFinishTest,AgentMessageResultQueryServiceTest test`

Expected: FAIL。

- [ ] **Step 3: 改造执行态和收尾签名**

`ChatTurnExecution` 使用仅供工作线程写入的结果列表，并对外只给不可变快照：

```java
final List<PendingMessageResult> results = new ArrayList<>();

void addResult(PendingMessageResult result) {
    results.add(Objects.requireNonNull(result));
}

List<PendingMessageResult> resultSnapshot() {
    return List.copyOf(results);
}
```

`ChatTurnFinishService.finish` 新增 `List<PendingMessageResult> results` 参数。在更新助手消息成功后、推进会话游标前批量插入结果，并把 `tenantId/userId/conversationId/requestId/messageId` 全部从 `ChatTurnContext` 覆盖写入，不能信任工具数据自带的归属字段。

- [ ] **Step 4: 批量加载历史结果**

```java
public record ChatMessageResultResponse(
        int resultSequence,
        String kind,
        int schemaVersion,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") OffsetDateTime queriedAt,
        JsonNode data) {
}
```

`AgentMessageQueryService` 先取得本页消息，再一次查询所有 `messageId` 的结果，按 `messageId → resultSequence` 分组。无效 JSON、未知 kind 或不支持的 schemaVersion 仅跳过该条并记录无正文告警，不得使整页历史失败。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=ChatTurnStructuredResultFinishTest,AgentMessageResultQueryServiceTest test`

Expected: PASS。

- [ ] **Step 6: 提交收尾和历史结果**

```bash
git add src/main/java/com/xjjk/agent/chat/service/stream src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java src/main/java/com/xjjk/agent/chat/api/dto src/main/java/com/xjjk/agent/chat/service/conversation/AgentMessageQueryService.java src/test/java/com/xjjk/agent/chat/service/turn/ChatTurnStructuredResultFinishTest.java src/test/java/com/xjjk/agent/chat/service/conversation/AgentMessageResultQueryServiceTest.java
git commit -m "feat: persist and restore structured chat results"
```

### Task 6: 确定性按钮动作，绕过模型工具选择

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatActionRequest.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamRequest.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/action/ChatActionType.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/action/ChatActionDispatcher.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/controller/ChatStreamController.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java`

- [ ] **Step 1: 写动作绕过模型失败测试**

```java
@Test
void queryOrderLogisticsActionExecutesGatewayWithoutCallingModel() {
    ChatStreamRequest request = new ChatStreamRequest(
            "conversation-1", "查看订单 O123 的物流",
            new ChatActionRequest("QUERY_ORDER_LOGISTICS", "O123"));

    runner.run(request, identity(), control, session, UUID.randomUUID().toString());

    verify(orderGateway).logistics("O123", OrderIdentifierType.ORDER_CODE,
            identity(), anyString());
    verifyNoInteractions(aiChatService);
    verify(session).result(eq("logistics-timeline"), eq(1), any(), any());
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=ChatActionDispatcherTest test`

Expected: FAIL。

- [ ] **Step 3: 定义唯一首版动作**

```java
public record ChatActionRequest(
        @NotBlank String type,
        @Size(max = 64) String orderCode) {
}

public enum ChatActionType {
    QUERY_ORDER_LOGISTICS
}
```

请求无 action 时保持现有自然语言模型路径。有 action 时必须校验 `message` 与动作参数均非空，但真正执行参数只取 `action.orderCode`；分发器固定调用订单物流 Gateway 并用后端模板生成“已为你查询订单 O123 的最新物流”正文。

- [ ] **Step 4: 在 Runner 中建立明确分支**

顺序固定为：开始事务 → session 事件 → 若为 action 则发送 `QUERYING_LOGISTICS` → 分发工具 → 发送固定 delta → 标记 SUCCESS → 统一 finalizer。动作路径不加载聊天上下文、不调用模型，但仍持久化用户消息、助手消息和结构化结果。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -Dtest=ChatActionDispatcherTest test`

Expected: PASS。

- [ ] **Step 6: 提交动作分发**

```bash
git add src/main/java/com/xjjk/agent/chat/api/dto/ChatActionRequest.java src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamRequest.java src/main/java/com/xjjk/agent/chat/action src/main/java/com/xjjk/agent/chat/api/controller/ChatStreamController.java src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/test/java/com/xjjk/agent/chat/action
git commit -m "feat: add deterministic chat card actions"
```

---

### Task 7: `order` 内部鉴权、可信身份与访问范围

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/config/AgentOrderApiProperties.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/config/AgentOrderWebConfiguration.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/security/AgentOrderAuthInterceptor.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/security/AgentRequestIdentity.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/security/AgentRequestIdentityResolver.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/domain/AgentOrderAccessMode.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/domain/AgentOrderAccessScope.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderAccessService.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/security/AgentOrderAuthInterceptorTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderAccessServiceTest.java`

- [ ] **Step 1: 写失败的身份和权限测试**

```java
@Test
void failsClosedWhenPrivilegeServiceReturnsNull() {
    when(privilege.getOrderPrivilege(10567L)).thenReturn(null);

    assertThatThrownBy(() -> service.resolve(identity(10567L, 10L, 1L)))
            .isInstanceOf(CustomException.class)
            .hasMessageContaining("订单权限暂时不可用");
}

@Test
void orgHeaderNeverAddsAnOrgOutsidePrivilegeResult() {
    UserPrivilegeDetailDTO dto = privilege(false, 10L, 11L);
    when(privilege.getOrderPrivilege(10567L)).thenReturn(dto);

    AgentOrderAccessScope scope = service.resolve(identity(10567L, 99L, 1L));

    assertThat(scope.getAllowedOrgIds()).containsExactlyInAnyOrder(10L, 11L);
    assertThat(scope.getAllowedOrgIds()).doesNotContain(99L);
}
```

同时测试：内部 Token 缺失/错误返回 401；`tenantId/userId/orgId` 非正数返回 400；`requestId` 非 UUID 返回 400；权限 `isGlobal=true` 映射 `GLOBAL`；非全局且有 `orgIds` 映射 `ORG_SCOPE`；非全局且空 `orgIds` 映射 `SELF`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentOrderAuthInterceptorTest,AgentOrderAccessServiceTest test`

Expected: FAIL，相关类型尚不存在。

- [ ] **Step 3: 实现可信身份解析**

`AgentRequestIdentityResolver` 只从以下请求头读取，任何正文同名字段都不参与身份构造：

```java
public AgentRequestIdentity resolve(HttpServletRequest request) {
    long tenantId = parsePositive(request.getHeader("X-Agent-Tenant-Id"), "tenantId");
    long userId = parsePositive(request.getHeader("X-Agent-User-Id"), "userId");
    long orgId = parsePositive(request.getHeader("X-Agent-Org-Id"), "orgId");
    String requestId = request.getHeader("X-Agent-Request-Id");
    UUID.fromString(requestId);
    return new AgentRequestIdentity(tenantId, userId, orgId, requestId);
}
```

内部 Token 使用与 Task 2 相同的常量时间比较，但请求头名称为 `X-Agent-Internal-Token`，路径限定 `/internal/agent/orders/**`。

- [ ] **Step 4: 实现不可缺省访问范围**

```java
public AgentOrderAccessScope resolve(AgentRequestIdentity identity) {
    UserPrivilegeDetailDTO privilege = privilegeFeignService.getOrderPrivilege(identity.getUserId());
    if (privilege == null || privilege.getIsGlobal() == null) {
        throw new CustomException("订单权限暂时不可用");
    }
    if (Boolean.TRUE.equals(privilege.getIsGlobal())) {
        return AgentOrderAccessScope.global(identity.getUserId(), identity.getOrgId());
    }
    List<Long> orgIds = privilege.getOrgIds() == null
            ? Collections.emptyList()
            : Arrays.stream(privilege.getOrgIds()).filter(Objects::nonNull).distinct()
                    .collect(Collectors.toList());
    return orgIds.isEmpty()
            ? AgentOrderAccessScope.self(identity.getUserId(), identity.getOrgId())
            : AgentOrderAccessScope.orgScope(identity.getUserId(), identity.getOrgId(), orgIds);
}
```

`AgentOrderAccessScope` 构造器必须拒绝：空模式、非正用户、`ORG_SCOPE` 空组织列表、`SELF` 携带组织列表。`loginOrgId` 只用于审计或缩小范围，禁止追加到权限服务返回的组织集合。

- [ ] **Step 5: 运行测试**

Run: `mvn -Dtest=AgentOrderAuthInterceptorTest,AgentOrderAccessServiceTest test`

Expected: PASS。

- [ ] **Step 6: 提交身份与权限基础设施**

```bash
git add src/main/java/com/xjjk/ec/oms/agent/config src/main/java/com/xjjk/ec/oms/agent/security src/main/java/com/xjjk/ec/oms/agent/domain src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderAccessService.java src/test/java/com/xjjk/ec/oms/agent/security src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderAccessServiceTest.java
git commit -m "feat: add fail-closed agent order access scope"
```

### Task 8: `order` 专用精确查询 Mapper 与索引验收

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapper.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryParam.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderRow.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsRow.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentRow.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapperTest.java`
- User-run DB artifact: index inspection and `EXPLAIN` SQL recorded in `D:/GitCode/order/docs/agent-order-index-verification.sql`

- [ ] **Step 1: 写 Mapper 失败测试**

```java
@Test
void selfScopeCannotReadAnotherUsersOrder() {
    AgentOrderQueryParam param = AgentOrderQueryParam.self("ORDER-OTHER", 10567L, 6);

    assertThat(mapper.findByOrderCode(param)).isEmpty();
}

@Test
void outerAndLogisticsLookupStillApplyOrgScope() {
    AgentOrderQueryParam param = AgentOrderQueryParam.orgScope(
            "OUT-1", 10567L, Arrays.asList(10L, 11L), 6);

    assertThat(mapper.findByOuterOrderCode(param))
            .allMatch(row -> Arrays.asList(10L, 11L).contains(row.getUserOrgId()));
}
```

测试数据库固定准备三组订单：本人订单、允许组织订单、无权组织订单；三种编号都必须跑同一权限矩阵。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentOrderQueryMapperTest test`

Expected: FAIL，Mapper 尚不存在。

- [ ] **Step 3: 实现三个独立精确入口**

`findByOrderCode` 以 `orders.Code = #{identifier}` 查询；`findByOuterOrderCode` 连接 `order_extend` 并以 `OuterOrderCode = #{identifier}` 查询；`findByLogisticsCode` 连接 `order_delivery_waybill_code` 并以 `LogisticsCode = #{identifier}` 查询。每个 SQL 都必须包含以下 `choose`，非法模式强制 `AND 1 = 0`：

```xml
<choose>
  <when test="accessMode == 'GLOBAL'">
  </when>
  <when test="accessMode == 'ORG_SCOPE' and allowedOrgIds != null and allowedOrgIds.size() > 0">
    AND o.UserOrgId IN
    <foreach collection="allowedOrgIds" item="orgId" open="(" separator="," close=")">
      #{orgId}
    </foreach>
  </when>
  <when test="accessMode == 'SELF'">
    AND o.UserId = #{userId}
  </when>
  <otherwise>
    AND 1 = 0
  </otherwise>
</choose>
```

三个入口均使用 `LIMIT #{limit}`，服务传 6 条用于判断是否超过对外上限 5。排序使用 `o.Id DESC`，禁止 `%keyword%`。

- [ ] **Step 4: 实现三个批量组装查询**

```java
List<AgentOrderGoodsRow> findGoodsByOrderIds(@Param("orderIds") Set<Long> orderIds);
List<AgentOrderShipmentRow> findShipmentsByOrderIds(@Param("orderIds") Set<Long> orderIds);
List<AgentOrderRow> findBaseByOrderIds(@Param("orderIds") Set<Long> orderIds);
```

商品 SQL 连接 `order_goods_detail → goods → goods_sku`，返回 `orderId/goodsName/skuCode/goodsModel/quantity`；运单 SQL 连接 `order_delivery_waybill_code → data_carrier`，返回 `orderId/logisticsCode/carrierId/carrierName`。每张表只在其真实表结构存在软删除列时增加有效数据条件，不得照搬一个不存在的 `IsDeleted` 字段。按订单 ID 集合一次读取，空集合时应用层不得调用。

- [ ] **Step 5: 检查索引并执行计划**

在 `docs/agent-order-index-verification.sql` 写入并由用户在目标库执行：

```sql
SHOW INDEX FROM ec.orders;
SHOW INDEX FROM ec.order_extend;
SHOW INDEX FROM ec.order_delivery_waybill_code;

EXPLAIN SELECT o.Id FROM ec.orders o
WHERE o.IsDeleted = 0 AND o.Code = '实际测试订单号' AND o.UserId = 10567
ORDER BY o.Id DESC LIMIT 6;

EXPLAIN SELECT o.Id FROM ec.order_extend e
JOIN ec.orders o ON o.Id = e.OrderId AND o.IsDeleted = 0
WHERE e.IsDeleted = 0 AND e.OuterOrderCode = '实际外部订单号'
  AND o.UserId = 10567
ORDER BY o.Id DESC LIMIT 6;

EXPLAIN SELECT o.Id FROM ec.order_delivery_waybill_code w
JOIN ec.orders o ON o.Id = w.OrderId AND o.IsDeleted = 0
WHERE w.IsDeleted = 0 AND w.LogisticsCode = '实际运单号'
  AND o.UserId = 10567
ORDER BY o.Id DESC LIMIT 6;
```

Expected: 编号查找不出现全表 `ALL`；若缺索引，先由 DBA 根据目标库现状评审索引，不在应用启动时自动建业务库索引。

- [ ] **Step 6: 运行 Mapper 测试**

Run: `mvn -Dtest=AgentOrderQueryMapperTest test`

Expected: PASS。

- [ ] **Step 7: 提交 Mapper**

```bash
git add src/main/java/com/xjjk/ec/oms/agent/dao src/test/java/com/xjjk/ec/oms/agent/dao docs/agent-order-index-verification.sql
git commit -m "feat: add permission-safe agent order queries"
```

### Task 9: `order` 订单聚合、字段白名单与状态映射

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderIdentifierType.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderSearchRequest.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderSearchResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderItemResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderGoodsResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderMaskingService.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryServiceTest.java`

- [ ] **Step 1: 写聚合失败测试**

```java
@Test
void autoStopsAtFirstMatchingIdentifierTypeAndBuildsBoundedCards() {
    when(mapper.findByOrderCode(any())).thenReturn(Collections.emptyList());
    when(mapper.findByOuterOrderCode(any())).thenReturn(orderRows(6));

    AgentOrderSearchResponse result = service.search(
            request("OUT-123", AgentOrderIdentifierType.AUTO), identity());

    verify(mapper, never()).findByLogisticsCode(any());
    assertThat(result.getMatchedBy()).isEqualTo(AgentOrderIdentifierType.OUTER_ORDER_CODE);
    assertThat(result.getItems()).hasSize(5);
    assertThat(result.isTruncated()).isTrue();
    assertThat(result.getItems()).allSatisfy(item -> {
        assertThat(item.getGoods()).hasSizeLessThanOrEqualTo(3);
        assertThat(item.toString()).doesNotContain("receiverTelephoneId", "provinceName");
    });
}
```

同时覆盖：空/超长/控制字符编号拒绝；显式类型只执行一个入口；金额保持分；状态通过 `OrderStatusConst.getValueByCode`；未知状态显示“未知状态”；姓名只保留首字和 `**`；商品和运单批量读取；重复运单去重但保持稳定顺序。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentOrderQueryServiceTest test`

Expected: FAIL，服务和 DTO 尚不存在。

- [ ] **Step 3: 实现查询顺序和有界聚合**

```java
private Match find(AgentOrderIdentifierType type, AgentOrderQueryParam base) {
    if (type == AgentOrderIdentifierType.ORDER_CODE || type == AgentOrderIdentifierType.AUTO) {
        List<AgentOrderRow> rows = mapper.findByOrderCode(base);
        if (!rows.isEmpty()) return new Match(AgentOrderIdentifierType.ORDER_CODE, rows);
    }
    if (type == AgentOrderIdentifierType.OUTER_ORDER_CODE || type == AgentOrderIdentifierType.AUTO) {
        List<AgentOrderRow> rows = mapper.findByOuterOrderCode(base);
        if (!rows.isEmpty()) return new Match(AgentOrderIdentifierType.OUTER_ORDER_CODE, rows);
    }
    if (type == AgentOrderIdentifierType.LOGISTICS_CODE || type == AgentOrderIdentifierType.AUTO) {
        List<AgentOrderRow> rows = mapper.findByLogisticsCode(base);
        if (!rows.isEmpty()) return new Match(AgentOrderIdentifierType.LOGISTICS_CODE, rows);
    }
    return Match.empty(type);
}
```

返回 DTO 不包含内部 `orderId`；内部 ID 仅用于批量组装。`queriedAt` 在 `order` 端生成并使用带偏移时间。订单不存在和无权访问都返回空 `items`，不能暴露不同错误。

- [ ] **Step 4: 运行测试**

Run: `mvn -Dtest=AgentOrderQueryServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交订单聚合**

```bash
git add src/main/java/com/xjjk/ec/oms/agent/api src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderMaskingService.java src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryServiceTest.java
git commit -m "feat: aggregate bounded agent order cards"
```

### Task 10: `order → silu-logistics` 客户端与部分降级

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/client/AgentLogisticsClient.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/client/AgentLogisticsFeignConfiguration.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/client/AgentLogisticsResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderLogisticsRequest.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderLogisticsResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderLogisticsService.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderLogisticsServiceTest.java`

- [ ] **Step 1: 写部分降级失败测试**

```java
@Test
void keepsAuthorizedOrderWhenLogisticsServiceTimesOut() {
    when(orderQuery.requireSingleAuthorizedOrder(any(), any())).thenReturn(order());
    when(logistics.query(anyString(), any())).thenThrow(new RetryableException(
            504, "timeout", Request.HttpMethod.POST, null, null, null));

    AgentOrderLogisticsResponse result = service.query(request(), identity());

    assertThat(result.isPartial()).isTrue();
    assertThat(result.getOrder().getOrderCode()).isEqualTo("O123");
    assertThat(result.getShipments()).allMatch(
            item -> item.getResultStatus().equals("DOWNSTREAM_UNAVAILABLE"));
}
```

同时测试：单订单正常轨迹、多订单标识拒绝并要求澄清、无运单映射 `NOT_SHIPPED`、下游返回部分成功原样保留、一次请求最多 10 个运单。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentOrderLogisticsServiceTest test`

Expected: FAIL。

- [ ] **Step 3: 实现独立 Feign 客户端**

```java
@FeignClient(
        name = "agent-logistics",
        url = "${integration.silu-logistics.base-url}",
        configuration = AgentLogisticsFeignConfiguration.class)
public interface AgentLogisticsClient {
    @PostMapping("/internal/agent/logistics/tracks/query")
    ReturnMsg<AgentTrackQueryResponse> query(
            @RequestHeader("X-Order-Internal-Token") String internalToken,
            @Valid @RequestBody AgentTrackQueryRequest request);
}
```

Feign 自身禁用隐式无限重试；应用只允许对连接异常、读取超时和明确的 502/503/504 最多再执行一次。权限、400、401、404 和合法空结果不重试。日志只记录 `requestId`、运单数量、耗时和结果状态，不记录完整运单号与轨迹正文。

- [ ] **Step 4: 实现物流查询服务**

服务先使用 Task 7 的权限入口定位唯一订单，再用内部订单 ID 批量取得运单。下游失败时构造每个运单的 `DOWNSTREAM_UNAVAILABLE`，保留订单概要并设置 `partial=true`；订单权限失败不得调用物流服务。

- [ ] **Step 5: 运行测试**

Run: `mvn -Dtest=AgentOrderLogisticsServiceTest test`

Expected: PASS。

- [ ] **Step 6: 提交下游适配器**

```bash
git add src/main/java/com/xjjk/ec/oms/agent/client src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderLogisticsRequest.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderLogisticsResponse.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderLogisticsService.java src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderLogisticsServiceTest.java
git commit -m "feat: aggregate authorized order logistics"
```

### Task 11: 发布 `order` 的两个 Agent 只读端点

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderController.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/api/AgentOrderControllerTest.java`

- [ ] **Step 1: 写端点失败测试**

```java
mockMvc.perform(post("/internal/agent/orders/search")
        .header("X-Agent-Internal-Token", "secret")
        .header("X-Agent-Tenant-Id", "1")
        .header("X-Agent-User-Id", "10567")
        .header("X-Agent-Org-Id", "10")
        .header("X-Agent-Request-Id", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"identifier\":\"O123\",\"identifierType\":\"AUTO\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].orderCode").value("O123"))
        .andExpect(jsonPath("$.data.items[0].orderId").doesNotExist());
```

再为 `/internal/agent/orders/logistics` 写相同身份头测试，并断言无 Token 为 401、非法身份头为 400、功能关闭时端点不存在。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentOrderControllerTest test`

Expected: FAIL。

- [ ] **Step 3: 实现薄控制器**

```java
@PostMapping("/search")
public ReturnMsg<AgentOrderSearchResponse> search(
        HttpServletRequest servletRequest,
        @Valid @RequestBody AgentOrderSearchRequest request) {
    AgentRequestIdentity identity = identityResolver.resolve(servletRequest);
    return ReturnUtil.success(orderQueryService.search(request, identity));
}

@PostMapping("/logistics")
public ReturnMsg<AgentOrderLogisticsResponse> logistics(
        HttpServletRequest servletRequest,
        @Valid @RequestBody AgentOrderLogisticsRequest request) {
    AgentRequestIdentity identity = identityResolver.resolve(servletRequest);
    return ReturnUtil.success(orderLogisticsService.query(request, identity));
}
```

- [ ] **Step 4: 运行端点测试和全量测试**

Run: `mvn -Dtest=AgentOrderControllerTest test`

Expected: PASS。

Run: `mvn test`

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交端点**

```bash
git add src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderController.java src/test/java/com/xjjk/ec/oms/agent/api/AgentOrderControllerTest.java
git commit -m "feat: expose internal agent order APIs"
```

---

## 跨仓库实施约束与后续顺序

按 Task 1～23 顺序实施，每个阶段都能独立编译、测试和关闭：

1. Task 1～6：Agent 先用 Mock 下游锁定通用 ToolContext、双输出、持久化和按钮动作契约。
2. Task 7～11：`order` 完成订单权限、批量聚合及对物流接口的 Mock 集成。
3. Task 12～15：`silu-logistics` 实现与 `order` 契约一致的内部批量轨迹接口。
4. Task 16～20：接通 Agent 会话引用与 Electron 卡片、动作、重启恢复。
5. Task 21～23：配置、故障矩阵、性能基线、灰度与文档验收；Task 22 之前不得开启全量开关。

所有仓库都可能存在用户未提交改动。每次只暂存当前任务列出的文件；禁止使用 `git add .`。旧业务服务保持 Java 11 和 `javax.validation`，Agent 使用 Java 21 和 `jakarta.validation`。

## 文件职责总览

### `silu-logistics`

- `agent/api/*`：内部 HTTP 契约及控制器。
- `agent/config/*`：接口开关、内部 Token、轨迹数量和刷新并发配置。
- `agent/security/*`：内部服务身份校验。
- `agent/service/AgentLogisticsTrackService.java`：批量查询总编排。
- `agent/service/AgentRouteSearchGateway.java`：只读 ES 查询。
- `agent/service/AgentCarrierRefreshService.java`：按 `data_carrier.Type` 定向刷新。
- `agent/service/AgentTrackSanitizer.java`：字段白名单、手机号掩码和排序裁剪。

### `order`

- `agent/api/*`：订单搜索和订单物流内部契约。
- `agent/config/*`、`agent/security/*`：接口开关、内部 Token 和可信身份解析。
- `agent/domain/AgentOrderAccessScope.java`：不可缺省的访问范围。
- `agent/dao/AgentOrderQueryMapper.java`：三个精确入口和三个批量组装查询。
- `agent/service/AgentOrderAccessService.java`：权限服务结果转换和失败关闭。
- `agent/service/AgentOrderQueryService.java`：编号匹配、聚合和脱敏。
- `agent/client/AgentLogisticsClient.java`：调用 `silu-logistics` 的内部批量接口。

### Agent server

- `tool/*`：通用 ToolContext、单轮调用预算、同参去重和结构化结果发布。
- `order/*`：订单/物流领域对象、Feign 适配器和 Spring AI 工具。
- `chat/result/*`：结构化结果的大小校验、持久化和历史加载。
- `chat/action/*`：前端按钮动作的确定性分发。
- `chat/reference/*`：会话内最近唯一订单引用。

### Electron frontend

- `contracts/chat.ts`：订单、物流、动作和历史结果协议。
- `stores/chat-accumulator.ts`：一条助手消息上的多结果累计。
- `stores/chat.ts`：流式发送、动作发送和历史恢复。
- `components/OrderListCard.vue`：订单列表卡片。
- `components/LogisticsTimelineCard.vue`：多运单轨迹。
- 主进程 HTTP/IPC：携带 Token 查询历史消息。

---

### Task 12: 锁定 `silu-logistics` 内部轨迹契约

**Files:**
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentTrackQueryRequest.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentTrackQueryResponse.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentShipmentTrackResponse.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentTrackNodeResponse.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentTrackResultStatus.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/api/AgentTrackContractTest.java`

- [ ] **Step 1: 写失败的契约测试**

```java
@Test
void rejectsMoreThanTenShipmentsAndCapsTraceNodesAtFifty() {
    AgentTrackQueryRequest request = new AgentTrackQueryRequest();
    request.setMaxTraceNodes(51);
    request.setShipments(IntStream.range(0, 11)
            .mapToObj(i -> new AgentTrackQueryRequest.Shipment("SF" + i, 2L))
            .collect(Collectors.toList()));

    Set<ConstraintViolation<AgentTrackQueryRequest>> violations =
            Validation.buildDefaultValidatorFactory().getValidator().validate(request);

    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("单次最多查询10个运单", "每个运单最多返回50条轨迹");
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentTrackContractTest test`

Expected: FAIL，原因是内部轨迹契约类尚不存在。

- [ ] **Step 3: 创建 Java 11 DTO 和状态枚举**

请求必须使用 `javax.validation`，核心定义如下：

```java
@Data
@NoArgsConstructor
public class AgentTrackQueryRequest {
    @Valid
    @NotEmpty(message = "运单列表不能为空")
    @Size(max = 10, message = "单次最多查询10个运单")
    private List<Shipment> shipments;

    @NotNull
    @Min(value = 1, message = "每个运单至少返回1条轨迹")
    @Max(value = 50, message = "每个运单最多返回50条轨迹")
    private Integer maxTraceNodes = 50;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Shipment {
        @NotBlank
        @Size(max = 64)
        private String logisticsCode;
        @NotNull
        @Positive
        private Long carrierId;
    }
}
```

```java
public enum AgentTrackResultStatus {
    SUCCESS,
    NOT_SHIPPED,
    NO_TRACE,
    REFRESH_TIMEOUT,
    DOWNSTREAM_UNAVAILABLE,
    UNSUPPORTED_REFRESH
}
```

响应只包含：`logisticsCode`、`resultStatus`、`latestStatusText`、`latestTrace`、`traces`；轨迹节点只包含 `time`、`location`、`description`。不得出现 `RouteDO.name`、`RouteDO.phone` 或承运商原始对象。

- [ ] **Step 4: 运行契约测试**

Run: `mvn -Dtest=AgentTrackContractTest test`

Expected: PASS。

- [ ] **Step 5: 提交契约**

```bash
git add src/main/java/com/xjjk/ec/logistics/agent/api src/test/java/com/xjjk/ec/logistics/agent/api
git commit -m "feat: define agent logistics track contract"
```

### Task 13: `silu-logistics` 内部鉴权和功能开关

**Files:**
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/config/AgentLogisticsApiProperties.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/config/AgentLogisticsWebConfiguration.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/security/AgentLogisticsAuthInterceptor.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/security/AgentLogisticsAuthInterceptorTest.java`

- [ ] **Step 1: 写鉴权失败测试**

```java
@Test
void rejectsMissingOrWrongInternalToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AgentLogisticsAuthInterceptor interceptor =
            new AgentLogisticsAuthInterceptor("order-to-logistics-secret");

    assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

    request.addHeader("X-Order-Internal-Token", "wrong-secret");
    response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentLogisticsAuthInterceptorTest test`

Expected: FAIL，原因是拦截器尚不存在。

- [ ] **Step 3: 实现常量时间 Token 校验**

```java
public boolean preHandle(HttpServletRequest request,
                         HttpServletResponse response,
                         Object handler) throws IOException {
    String supplied = request.getHeader("X-Order-Internal-Token");
    boolean matched = supplied != null && MessageDigest.isEqual(
            expectedToken.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8));
    if (matched) {
        return true;
    }
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    return false;
}
```

`AgentLogisticsWebConfiguration` 仅在 `agent.internal-api.enabled=true` 时注册 Bean，并把拦截器限定在 `/internal/agent/logistics/**`。`AgentLogisticsApiProperties` 在开关开启时校验 Token 非空、`maxShipments` 为 1～10、`maxTraceNodes` 为 1～50、刷新线程池和队列均为正数。

- [ ] **Step 4: 运行鉴权测试**

Run: `mvn -Dtest=AgentLogisticsAuthInterceptorTest test`

Expected: PASS。

- [ ] **Step 5: 提交鉴权基础设施**

```bash
git add src/main/java/com/xjjk/ec/logistics/agent/config src/main/java/com/xjjk/ec/logistics/agent/security src/test/java/com/xjjk/ec/logistics/agent/security
git commit -m "feat: secure agent logistics endpoint"
```

### Task 14: ES 优先、按承运商定向刷新和脱敏

**Files:**
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/service/AgentRouteSearchGateway.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/service/AgentCarrierRefreshService.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/service/AgentTrackSanitizer.java`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/service/AgentLogisticsTrackService.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/service/AgentLogisticsTrackServiceTest.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/service/AgentTrackSanitizerTest.java`

- [ ] **Step 1: 写查询编排失败测试**

```java
@Test
void refreshesOnlyTheCarrierResolvedFromCarrierIdAfterEsMiss() {
    when(search.find("SF123")).thenReturn(Optional.empty(), Optional.of(route("SF123")));
    when(carriers.selectByPrimaryKey(6L)).thenReturn(carrier(388));

    AgentShipmentTrackResponse result = service.queryOne("SF123", 6L, 50);

    verify(refresh).refresh(388, "SF123");
    verify(refresh, never()).refresh(eq(390), anyString());
    verify(refresh, never()).refresh(eq(389), anyString());
    verify(refresh, never()).refresh(eq(1184), anyString());
    assertThat(result.getResultStatus()).isEqualTo(AgentTrackResultStatus.SUCCESS);
}
```

同时覆盖：ES 命中不刷新、ES 故障不刷新、未知 `carrierId` 不刷新、不支持的 `Type` 返回 `UNSUPPORTED_REFRESH`、刷新超时返回 `REFRESH_TIMEOUT`、批量中单个失败不影响其他运单。

- [ ] **Step 2: 写脱敏和排序失败测试**

```java
@Test
void sortsNewestFirstCapsNodesAndRemovesCourierIdentity() {
    RouteDO route = routeWithDetails(
            detail("2026-09-07 09:00:00", "南京", "派件员张三 13812345678"),
            detail("2026-09-07 11:00:00", "南京", "到达南京转运中心"));

    List<AgentTrackNodeResponse> nodes = sanitizer.sanitize(route, 1);

    assertThat(nodes).hasSize(1);
    assertThat(nodes.get(0).getDescription()).isEqualTo("到达南京转运中心");
    assertThat(nodes.get(0).toString()).doesNotContain("张三", "13812345678");
}
```

- [ ] **Step 3: 运行测试并确认失败**

Run: `mvn -Dtest=AgentLogisticsTrackServiceTest,AgentTrackSanitizerTest test`

Expected: FAIL，原因是服务尚不存在。

- [ ] **Step 4: 实现编排**

`AgentRouteSearchGateway.find(code)` 复用现有 `ElasticSearchClient` 和 `EsConstants.ROUTE_INDEX`，只执行 `routingNumber` 精确查询并把空列表映射为 `Optional.empty()`。

`AgentCarrierRefreshService.refresh(type, code)` 使用有界线程池和 `Future.get(refreshTimeout)`，映射固定如下：

```java
switch (carrierType) {
    case 390:
        callService.syncEmsRouteForEs(Collections.singleton(logisticsCode));
        return true;
    case 389:
        callService.syncDepponRouteForEs(Collections.singleton(logisticsCode));
        return true;
    case 388:
        callService.syncSfRouteForEs(Collections.singleton(logisticsCode));
        return true;
    case 1184:
        callService.syncJdRouteForEs(logisticsCode);
        return true;
    default:
        return false;
}
```

`carrierId` 必须先通过 `DataCarrierMapper.selectByPrimaryKey` 解析为 `data_carrier.Type`；不得直接用 ID 进入上述 `switch`。一次处理顺序固定为：查询 ES → 根据已知类型刷新一次 → 再查 ES 一次 → 返回状态。手机号按 `前三位 + **** + 后四位` 掩码，`name`、`phone`、`opCode` 不进入响应 DTO。

- [ ] **Step 5: 运行服务测试**

Run: `mvn -Dtest=AgentLogisticsTrackServiceTest,AgentTrackSanitizerTest test`

Expected: PASS。

- [ ] **Step 6: 提交查询服务**

```bash
git add src/main/java/com/xjjk/ec/logistics/agent/service src/test/java/com/xjjk/ec/logistics/agent/service
git commit -m "feat: add bounded agent logistics query"
```

### Task 15: 发布 `silu-logistics` 内部批量端点

**Files:**
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/api/AgentLogisticsController.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/api/AgentLogisticsControllerTest.java`

- [ ] **Step 1: 写 MockMvc 失败测试**

```java
mockMvc.perform(post("/internal/agent/logistics/tracks/query")
        .header("X-Order-Internal-Token", "secret")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"shipments\":[{\"logisticsCode\":\"SF123\",\"carrierId\":6}],\"maxTraceNodes\":50}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].logisticsCode").value("SF123"))
        .andExpect(jsonPath("$.data.items[0].traces[0].phone").doesNotExist());
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -Dtest=AgentLogisticsControllerTest test`

Expected: FAIL，端点尚不存在。

- [ ] **Step 3: 添加只做校验和委托的控制器**

```java
@RestController
@RequestMapping("/internal/agent/logistics")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.internal-api.enabled", havingValue = "true")
public class AgentLogisticsController {
    private final AgentLogisticsTrackService trackService;

    @PostMapping("/tracks/query")
    public ReturnMsg<AgentTrackQueryResponse> query(@Valid @RequestBody AgentTrackQueryRequest request) {
        return ReturnUtil.success(trackService.query(request));
    }
}
```

- [ ] **Step 4: 运行端点测试和全量测试**

Run: `mvn -Dtest=AgentLogisticsControllerTest test`

Expected: PASS。

Run: `mvn test`

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交端点**

```bash
git add src/main/java/com/xjjk/ec/logistics/agent/api/AgentLogisticsController.java src/test/java/com/xjjk/ec/logistics/agent/api/AgentLogisticsControllerTest.java
git commit -m "feat: expose internal agent logistics API"
```

---

### Task 16: 会话内唯一订单引用与上下文预算

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/reference/RecentOrderReference.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/reference/RecentOrderReferenceProvider.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/reference/ChatBusinessContextRenderer.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/result/AgentMessageResultMapper.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/memory/ChatContextPreparationService.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/memory/ChatContextSelector.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/memory/RequestChatMemory.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/domain/memory/ChatContextSelection.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/reference/RecentOrderReferenceProviderTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/service/memory/ChatContextWithBusinessReferenceTest.java`

- [ ] **Step 1: 写归属、唯一性和预算失败测试**

```java
@Test
void exposesOnlyOneRecentOrderReferenceFromTheSameOwnedConversation() {
    givenLatestResult(owner(), 23L, orderList("O123"));

    Optional<RecentOrderReference> reference = provider.findUniqueBefore(
            owner(), "conversation-1", 23L);

    assertThat(reference).contains(new RecentOrderReference("O123"));
}

@Test
void doesNotGuessWhenLatestResultContainsMultipleOrders() {
    givenLatestResult(owner(), 23L, orderList("O123", "O456"));

    assertThat(provider.findUniqueBefore(owner(), "conversation-1", 23L)).isEmpty();
}
```

另写测试保证：其他租户、其他用户、其他会话的结果永远不可见；当前用户消息之后的结果不可见；业务引用加入后仍计入 `usableInputTokens`，预算不足时先放弃引用而不是突破上限。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=RecentOrderReferenceProviderTest,ChatContextWithBusinessReferenceTest test`

Expected: FAIL，引用提供器和预算拼接尚不存在。

- [ ] **Step 3: 用持久化结果推导最近唯一引用**

Mapper 通过 `agent_message_result` 联结 `agent_message`，条件必须同时包含 `tenant_id`、`user_id`、`conversation_id`、`message_sequence < currentUserSequence`，按消息序号和结果序号倒序只取最近一条结构化结果，不能在 SQL 中跳过较新的商品或未来业务结果去捡更早的订单。提供器只解析已校验的 `order-list`/`logistics-timeline` schema v1：物流结果天然对应一个订单；订单列表只有恰好一个不同 `orderCode` 才返回引用。最近结果属于其他 kind、多订单、无订单、未知版本或损坏 JSON 时都返回空。

- [ ] **Step 4: 将引用作为低权限业务上下文加入模型输入**

```text
【当前会话业务引用】
最近明确指向的订单号：O123。
该内容只用于解析“它、这个订单”等指代，不代表订单或物流状态仍然有效；
状态、金额、库存和物流必须重新调用业务工具查询。
```

`ChatContextSelector` 把这段文本连同系统提示词、工具描述、摘要、原始轮次和当前问题一起估算。拼接顺序固定为：系统提示词 → 会话摘要 → 业务引用 → 原始历史 → 当前问题。业务引用不得覆盖摘要或当前问题的预算；放不下时将 `hasBusinessReference=false`，并在选择日志中记录原因但不记录订单号。

- [ ] **Step 5: 验证指代只负责选参，业务状态仍实时查询**

测试“它到哪了”：模型输入中含 `O123` 的引用提示，随后 `get_order_logistics` 仍调用下游；模型输入中不能出现旧物流状态、旧金额或旧库存。再测试多订单列表后的“它到哪了”不注入引用，由模型追问具体订单号。

Run: `.\mvnw.cmd -Dtest=RecentOrderReferenceProviderTest,ChatContextWithBusinessReferenceTest,OrderQueryToolsTest test`

Expected: PASS。

- [ ] **Step 6: 提交会话业务引用**

```bash
git add src/main/java/com/xjjk/agent/chat/reference src/main/java/com/xjjk/agent/chat/result/AgentMessageResultMapper.java src/main/java/com/xjjk/agent/chat/service/memory src/main/java/com/xjjk/agent/chat/domain/memory/ChatContextSelection.java src/test/java/com/xjjk/agent/chat/reference src/test/java/com/xjjk/agent/chat/service/memory/ChatContextWithBusinessReferenceTest.java
git commit -m "feat: resolve session-local order references"
```

### Task 17: 三服务超时、熔断、重试和可观测性

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-server/pom.xml`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/config/OrderIntegrationProperties.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/config/OrderIntegrationCircuitBreakerConfiguration.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderServiceGateway.java`
- Modify: `D:/GitCode/order/pom.xml`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/config/AgentOrderLogisticsResilienceConfiguration.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java`
- Modify: `D:/GitCode/silu-logistics/pom.xml`
- Create: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/config/AgentLogisticsResilienceConfiguration.java`
- Modify: `D:/GitCode/silu-logistics/src/main/java/com/xjjk/ec/logistics/agent/service/AgentLogisticsTrackService.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/client/OrderServiceResilienceTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderPartialDegradationTest.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/service/AgentLogisticsResilienceTest.java`

- [ ] **Step 1: 写瞬时故障和非重试故障测试**

测试矩阵固定为：连接失败、读取超时、HTTP 502/503/504 最多重试一次；400、401、403、404、业务权限拒绝和参数错误绝不重试；连续失败打开熔断器；半开探测成功后恢复。一次聊天内总工具调用上限仍为 3，网络重试不计作新的模型工具调用。

- [ ] **Step 2: 运行测试并确认失败**

Run (Agent): `.\mvnw.cmd -Dtest=OrderServiceResilienceTest test`

Run (`order`): `mvn -Dtest=AgentOrderPartialDegradationTest test`

Run (`silu-logistics`): `mvn -Dtest=AgentLogisticsResilienceTest test`

Expected: FAIL，尚未建立隔离的策略和指标。

- [ ] **Step 3: 加入 Resilience4j 并按依赖方向隔离**

三个工程均加入与各自 Spring Cloud 版本匹配的 `spring-cloud-starter-circuitbreaker-resilience4j`。实例名固定：`agentOrderSearch`、`agentOrderLogistics`、`orderLogisticsTracks`、`logisticsProviderRefresh`。不得用一个总熔断器把订单库查询和物流供应商故障绑在一起。Spring Cloud 2020 项目只使用其 BOM 管理的版本，不手写 Resilience4j 版本号。

- [ ] **Step 4: 实现明确的异常分类和部分降级**

Agent 订单列表失败返回 `ORDER_SERVICE_UNAVAILABLE`；订单存在但物流下游失败时仍返回订单卡和每个运单的 `DOWNSTREAM_UNAVAILABLE` 状态。`silu-logistics` 的一个运单刷新失败不能使同批其他运单失败。所有下游超时都必须小于 SSE 总超时，并给最终收尾留出至少 5 秒。

- [ ] **Step 5: 增加不含敏感数据的指标**

记录：`toolName`、`requestId`、`durationMs`、`matchType`、`resultCount`、`shipmentCount`、`retryCount`、`circuitState`、`degradedCount`、`status`。不得记录内部 Token、姓名、电话、地址、完整请求/响应 JSON。Micrometer 计数器和计时器使用有限标签，订单号和 requestId 只能写日志字段，不能作为指标标签。

- [ ] **Step 6: 运行三服务测试并分别提交**

Run: `.\mvnw.cmd -Dtest=OrderServiceResilienceTest test`

Run: `mvn -Dtest=AgentOrderPartialDegradationTest test`

Run: `mvn -Dtest=AgentLogisticsResilienceTest test`

Expected: 全部 PASS。

在每个仓库只提交该仓库文件，提交信息分别为：`feat: harden agent order integration`、`feat: isolate logistics dependency failures`、`feat: harden agent logistics queries`。

### Task 18: 前端结构化契约、累加器和独立卡片组件

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/contracts/chat.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.ts`
- Create: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/OrderListCard.vue`
- Create: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/LogisticsTimelineCard.vue`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.vue`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/assets/main.css`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.test.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/OrderListCard.test.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/LogisticsTimelineCard.test.ts`

- [ ] **Step 1: 写 SSE 多结果和未知版本失败测试**

```ts
it('keeps multiple typed results in server order', () => {
  const state = createAccumulator()
  state.accept(resultEvent('order-list', 1, orderListData()))
  state.accept(resultEvent('logistics-timeline', 1, logisticsData()))

  expect(state.results.map((it) => it.kind)).toEqual([
    'order-list',
    'logistics-timeline'
  ])
})
```

未知 `kind` 或不支持的 `schemaVersion` 忽略该结果并保留正文；重复 SSE `id` 不得追加第二次。

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- --run src/renderer/src/stores/chat-accumulator.test.ts src/renderer/src/components/OrderListCard.test.ts src/renderer/src/components/LogisticsTimelineCard.test.ts`

Expected: FAIL。

- [ ] **Step 3: 用判别联合替换旧占位订单契约**

`ChatResult` 定义为 `ProductListResult | OrderListResult | LogisticsTimelineResult`，公共字段含 `kind`、`schemaVersion`、`queriedAt`。删除旧 `order-summary` 占位类型。金额保持后端返回的展示字符串；前端不自行进行分到元换算。所有运行时解析先校验 kind/version 和必要数组字段，再进入 store。

- [ ] **Step 4: 创建订单列表卡片**

订单卡片显示订单名称、订单状态、下单时间、脱敏收件人、实付金额、商品摘要、承运商和运单号；不重复实现商品搜索卡片中的规格、价格、库存和上下架字段。订单卡不展示封面图。详情按钮首版禁用并写“详情功能建设中”，物流按钮只有唯一订单号时可用。

- [ ] **Step 5: 创建多包裹物流时间线**

每个运单独立显示承运商、运单号、查询状态和轨迹；节点按时间倒序，默认展开最新 3 条，可展开至后端已返回的最多 50 条。`NOT_SHIPPED`、`NO_TRACE`、`REFRESH_TIMEOUT`、`DOWNSTREAM_UNAVAILABLE`、`UNSUPPORTED_REFRESH` 分别给出稳定文案，部分失败不遮盖成功包裹。

- [ ] **Step 6: 运行前端测试、类型检查和提交**

Run: `npm test -- --run`

Expected: PASS。

Run: `npm run typecheck && npm run lint`

Expected: PASS。

```bash
git add src/renderer/src/contracts/chat.ts src/renderer/src/stores/chat-accumulator.ts src/renderer/src/components/OrderListCard.vue src/renderer/src/components/LogisticsTimelineCard.vue src/renderer/src/components/ChatWindow.vue src/renderer/src/assets/main.css src/renderer/src/stores/chat-accumulator.test.ts src/renderer/src/components/OrderListCard.test.ts src/renderer/src/components/LogisticsTimelineCard.test.ts
git commit -m "feat: render order and logistics result cards"
```

### Task 19: 前端卡片动作走受控 IPC 流程

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/shared/desktop.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/ipc.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/preload/index.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/preload/index.d.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/chat-transport.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/OrderListCard.vue`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.test.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.test.ts`

- [ ] **Step 1: 写动作请求校验和点击行为失败测试**

```ts
it('sends a typed logistics action without exposing the token', async () => {
  await transport.stream({
    conversationId: 'conversation-1',
    message: '查看订单 O123 的物流',
    action: { type: 'QUERY_ORDER_LOGISTICS', orderCode: 'O123' }
  })

  expect(desktop.startChatStream).toHaveBeenCalledWith(expect.objectContaining({
    action: { type: 'QUERY_ORDER_LOGISTICS', orderCode: 'O123' }
  }))
  expect(window.localStorage.getItem('accessToken')).toBeNull()
})
```

另测非法动作类型、空订单号、超长订单号在主进程发 HTTP 前被拒绝；重复点击期间按钮禁用；点击不会把旧卡片结果直接当作新状态显示。

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- --run src/main/agent-chat-stream-client.test.ts src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/components/ChatWindow.test.ts`

Expected: FAIL。

- [ ] **Step 3: 扩展跨进程白名单契约**

```ts
export type ChatAction = {
  type: 'QUERY_ORDER_LOGISTICS'
  orderCode: string
}

export interface ChatStreamRequest {
  conversationId?: string
  message: string
  action?: ChatAction
}
```

主进程 `validateRequest` 对 `action` 做严格对象形状校验，只接受白名单动作和 1～64 字符订单号；忽略原型链属性并在 `JSON.stringify` 前重建干净对象。Token 仍只由 `AuthSession` 在主进程读取。

- [ ] **Step 4: 将卡片事件映射成统一聊天请求**

`OrderListCard` 只向上发 `{ type, orderCode }`，store 生成显示文案并沿现有 SSE transport 发送。成功、失败、取消都恢复按钮状态；后端 404/403 使用统一“订单不存在或无权访问”，前端不根据状态猜测权限。

- [ ] **Step 5: 运行安全测试和提交**

Run: `npm test -- --run`

Expected: PASS。

Run: `npm run typecheck && npm run lint`

Expected: PASS。

```bash
git add src/shared/desktop.ts src/main/agent-chat-stream-client.ts src/main/agent-chat-stream-client.test.ts src/main/ipc.ts src/preload/index.ts src/preload/index.d.ts src/renderer/src/transports src/renderer/src/stores/chat.ts src/renderer/src/components/OrderListCard.vue src/renderer/src/components/ChatWindow.test.ts
git commit -m "feat: add secure order card actions"
```

### Task 20: 登录后恢复当前会话和结构化卡片

**Files:**
- Create: `D:/GitCode/order-logistics-agent-web/src/main/agent-conversation-client.ts`
- Create: `D:/GitCode/order-logistics-agent-web/src/main/agent-conversation-client.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/ipc.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/index.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/preload/index.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/preload/index.d.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/shared/desktop.ts`
- Create: `D:/GitCode/order-logistics-agent-web/src/renderer/src/services/active-conversation-repository.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/services/active-conversation-repository.test.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-store-contract.test.ts`

- [ ] **Step 1: 写历史恢复失败测试**

测试覆盖：登录用户按 `tenantId:userId` 读取自己的活动会话 ID；主进程携带当前 Token 调用 `GET /api/v1/conversations/{id}/messages?pageSize=100`；正文和结果按 `messageSequence/resultSequence` 恢复；404/403 清除本地指针并开始新会话；401 触发现有重新登录流程；损坏的本地值或未知结果版本不使窗口崩溃。

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- --run src/main/agent-conversation-client.test.ts src/renderer/src/services/active-conversation-repository.test.ts src/renderer/src/stores/chat-store-contract.test.ts`

Expected: FAIL。

- [ ] **Step 3: 在主进程增加历史读取客户端**

`AgentConversationClient` 的构造参数与流客户端一致，只从 `AuthSession` 读取 Token，设置 3 秒连接/首包超时并限制响应体为 4 MiB。响应必须校验顶层 `code=SUCCESS`、`items` 数组、分页游标和每条消息必要字段。IPC 只返回脱敏历史 DTO，绝不返回响应头或 Token。

- [ ] **Step 4: 保存按身份隔离的活动会话指针**

键名固定为 `agent-active-conversation:v1:<tenantId>:<userId>`，值只允许 UUID。收到首个 `session` 事件后保存；退出登录只切换身份，不批量删除其他用户指针。因为后端每次都重新校验 tenant/user，客户端指针不能构成越权凭据。

- [ ] **Step 5: 恢复最近 100 条并支持继续向前分页**

登录成功进入聊天窗口时只恢复一页，防止启动时无限拉取；有 `hasMore=true` 时显示“加载更早消息”，使用 `nextBeforeSequence` 继续分页并去重。恢复完成前禁用发送按钮；恢复失败显示可重试状态，但允许用户明确选择“开始新对话”。

- [ ] **Step 6: 验证重启恢复和提交**

Run: `npm test -- --run`

Expected: PASS。

Run: `npm run typecheck && npm run lint && npm run build`

Expected: PASS / BUILD SUCCESS。

```bash
git add src/main/agent-conversation-client.ts src/main/agent-conversation-client.test.ts src/main/ipc.ts src/main/index.ts src/preload src/shared/desktop.ts src/renderer/src/services/active-conversation-repository.ts src/renderer/src/services/active-conversation-repository.test.ts src/renderer/src/stores/chat.ts src/renderer/src/stores/chat-store-contract.test.ts
git commit -m "feat: restore chat results after restart"
```

### Task 21: 在 Nacos 配置三服务参数并保持密钥只来自环境变量

**Files:**
- User-update: Agent Nacos Data ID currently imported by `order-logistics-agent-server`
- User-update: `order` Nacos Data ID for the active environment
- User-update: `silu-logistics` Nacos Data ID for the active environment
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/config/OrderIntegrationConfigurationTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/config/AgentOrderConfigurationTest.java`
- Test: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/config/AgentLogisticsConfigurationTest.java`

- [ ] **Step 1: 写功能关闭和必填密钥失败测试**

三个测试都验证：功能开关为 `false` 时内部控制器或模型工具不存在；开关为 `true` 但 Token、下游地址或上限缺失时应用启动失败并给出具体配置项；任何 `application.properties/yml` 都不提供真实密钥或弱默认值。

- [ ] **Step 2: 由用户把 Agent 配置加入 Nacos**

```properties
# 订单与物流工具先关闭，完成影子验证后再按灰度步骤开启
agent.tool.order.enabled=false
agent.tool.logistics.enabled=false
agent.tool.order.rollout-mode=ALLOWLIST
agent.tool.logistics.rollout-mode=ALLOWLIST
agent.tool.order.allowed-org-ids=
agent.tool.logistics.allowed-org-ids=

# 地址由部署环境注入；本机也显式写入 .env.local，不在 Nacos 猜端口
integration.order.base-url=${ORDER_SERVICE_BASE_URL}
integration.order.internal-token=${ORDER_AGENT_INTERNAL_TOKEN}

# 展示与持久化硬上限
agent.order.max-results=5
agent.order.max-goods-per-order=3
agent.logistics.max-shipments=10
agent.logistics.max-trace-nodes=50
agent.chat.result.max-payload-bytes=1048576
agent.chat.reference.enabled=true

# Feign 本身不自动重试；代码只对允许的瞬时故障显式重试一次
spring.cloud.openfeign.client.config.order-agent-search.connect-timeout=1000
spring.cloud.openfeign.client.config.order-agent-search.read-timeout=2500
spring.cloud.openfeign.client.config.order-agent-search.logger-level=basic
spring.cloud.openfeign.client.config.order-agent-logistics.connect-timeout=1000
spring.cloud.openfeign.client.config.order-agent-logistics.read-timeout=8000
spring.cloud.openfeign.client.config.order-agent-logistics.logger-level=basic

resilience4j.circuitbreaker.instances.agentOrderSearch.sliding-window-size=20
resilience4j.circuitbreaker.instances.agentOrderSearch.minimum-number-of-calls=10
resilience4j.circuitbreaker.instances.agentOrderSearch.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.agentOrderSearch.wait-duration-in-open-state=30s
resilience4j.circuitbreaker.instances.agentOrderLogistics.sliding-window-size=20
resilience4j.circuitbreaker.instances.agentOrderLogistics.minimum-number-of-calls=10
resilience4j.circuitbreaker.instances.agentOrderLogistics.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.agentOrderLogistics.wait-duration-in-open-state=30s
```

- [ ] **Step 3: 由用户把 `order` 配置加入 Nacos**

```properties
agent.internal-api.enabled=false
agent.internal-api.token=${ORDER_AGENT_INTERNAL_TOKEN}
agent.order.max-results=5
agent.order.max-goods-per-order=3
agent.order.max-shipments=10

integration.silu-logistics.base-url=${SILU_LOGISTICS_SERVICE_BASE_URL}
integration.silu-logistics.internal-token=${ORDER_LOGISTICS_INTERNAL_TOKEN}
spring.cloud.openfeign.client.config.agent-logistics.connect-timeout=1000
spring.cloud.openfeign.client.config.agent-logistics.read-timeout=6500
spring.cloud.openfeign.client.config.agent-logistics.logger-level=basic

resilience4j.circuitbreaker.instances.orderLogisticsTracks.sliding-window-size=20
resilience4j.circuitbreaker.instances.orderLogisticsTracks.minimum-number-of-calls=10
resilience4j.circuitbreaker.instances.orderLogisticsTracks.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.orderLogisticsTracks.wait-duration-in-open-state=30s
```

- [ ] **Step 4: 由用户把 `silu-logistics` 配置加入 Nacos**

```properties
agent.internal-api.enabled=false
agent.internal-api.token=${ORDER_LOGISTICS_INTERNAL_TOKEN}
agent.logistics.max-shipments=10
agent.logistics.max-trace-nodes=50
agent.logistics.es-query-timeout=1500ms
agent.logistics.carrier-refresh-timeout=3500ms
agent.logistics.refresh-core-pool-size=2
agent.logistics.refresh-max-pool-size=4
agent.logistics.refresh-queue-capacity=100

resilience4j.circuitbreaker.instances.logisticsProviderRefresh.sliding-window-size=20
resilience4j.circuitbreaker.instances.logisticsProviderRefresh.minimum-number-of-calls=10
resilience4j.circuitbreaker.instances.logisticsProviderRefresh.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.logisticsProviderRefresh.wait-duration-in-open-state=30s
```

本机 `.env.local` 需要提供 `ORDER_SERVICE_BASE_URL`、`SILU_LOGISTICS_SERVICE_BASE_URL`、`ORDER_AGENT_INTERNAL_TOKEN`、`ORDER_LOGISTICS_INTERNAL_TOKEN`；两个 Token 必须不同、足够随机，且不提交 Git。生产环境由部署平台 Secret 注入同名变量。

- [ ] **Step 5: 验证配置绑定并提交测试**

Run (Agent): `.\mvnw.cmd -Dtest=OrderIntegrationConfigurationTest test`

Run (`order`): `mvn -Dtest=AgentOrderConfigurationTest test`

Run (`silu-logistics`): `mvn -Dtest=AgentLogisticsConfigurationTest test`

Expected: 全部 PASS；启动日志只显示开关和上限，不显示 Token 值。

分别在三个仓库提交配置绑定测试，真实 Nacos 内容和 `.env.local` 不提交。

### Task 22: 端到端、安全、故障和性能验收后灰度开启

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/docs/runbooks/order-logistics-tool-verification.md`
- Create: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/OrderLogisticsToolEndToEndTest.java`
- Create: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/api/AgentOrderAuthorizationIntegrationTest.java`
- Create: `D:/GitCode/silu-logistics/src/test/java/com/xjjk/ec/logistics/agent/api/AgentLogisticsPartialFailureIntegrationTest.java`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.test.ts`

- [ ] **Step 1: 建立可重复的非生产测试数据**

选择三组不会包含真实个人信息的数据：单订单单包裹、单订单多包裹、无轨迹/不支持刷新。记录订单号时只放到本地未跟踪的验收记录，不写入仓库。验证数据必须覆盖坐席自己、允许组织、禁止组织三种归属。

- [ ] **Step 2: 跑服务级安全测试**

断言：缺少/错误内部 Token 为 401 或 403；伪造 tenant/user/org 不能越权；权限服务不可用时失败关闭；不存在与无权访问对外同为 `ORDER_NOT_FOUND_OR_FORBIDDEN`；响应和日志无完整手机、地址、派送员姓名、内部数据库 ID 和 Token。

Run (`order`): `mvn -Dtest=AgentOrderAuthorizationIntegrationTest test`

Expected: PASS。

- [ ] **Step 3: 跑物流故障矩阵**

断言：ES 命中不刷新；ES 未命中只刷新解析出的承运商；未知承运商不广播请求；一个包裹超时其余仍成功；轨迹最多 50 条且倒序；电话二次掩码。

Run (`silu-logistics`): `mvn -Dtest=AgentLogisticsPartialFailureIntegrationTest test`

Expected: PASS。

- [ ] **Step 4: 跑 Agent 与桌面端完整链路**

场景固定为：自然语言订单号查询 → 订单卡片 → 点击物流 → 多包裹时间线 → 退出并重启 → 正文和卡片恢复 → 输入“它到哪了” → 再次实时查询。检查 SSE 顺序为 `session/status/result*/delta*/done`，同一结果不重复，数据库结果与助手消息同事务，工具调用不超过 3 次。

Run (Agent): `.\mvnw.cmd -Dtest=OrderLogisticsToolEndToEndTest test`

Run (frontend): `npm test -- --run && npm run typecheck && npm run lint && npm run build`

Expected: 全部 PASS。

- [ ] **Step 5: 验证容量与超时边界**

使用测试环境执行 20 并发、持续 5 分钟的只读查询；验收 P95、错误率、连接池、刷新线程池队列、熔断器状态和 SSE 完结率。硬门槛：无越权、无线程/连接持续增长、无超出结果字节上限、超时请求均能完成聊天收尾。具体 P95 目标在首次基线测试后写入 runbook 并作为后续发布门槛，不能凭空承诺数值。

- [ ] **Step 6: 按依赖方向灰度开关**

顺序不可颠倒：先开启 `silu-logistics agent.internal-api.enabled` → 内部探测成功 → 开启 `order agent.internal-api.enabled` → 内部探测成功 → Agent 仅对测试组织开启订单工具 → 观察指标 → 开启物流工具 → 扩大灰度。任一步失败只回退相应开关，不回滚数据库结果表。网关 `/agent/**` 仍只路由到 Agent 服务，不暴露两个内部端点。

- [ ] **Step 7: 提交验收资产**

```bash
git add docs/runbooks/order-logistics-tool-verification.md src/test/java/com/xjjk/agent/order/OrderLogisticsToolEndToEndTest.java
git commit -m "test: verify order logistics tool chain"
```

另外两个仓库和前端测试分别独立提交，禁止用一次跨仓库提交掩盖失败。

### Task 23: 补齐生产说明与面试复盘材料

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/docs/architecture/order-logistics-tool-chain.md`
- Create: `D:/GitCode/order-logistics-agent-server/docs/interview/order-logistics-agent-interview-notes.md`
- Modify: `C:/Users/shwfo/Desktop/AI应用/Spring AI Agent项目学习记录.md`

- [ ] **Step 1: 写架构文档并用代码链接校验**

架构文档完整说明：为什么 Agent 只依赖 `order`；可信身份和权限收缩；订单聚合避免 N+1；`carrierId → data_carrier.Type → 单承运商刷新`；模型文本与 UI 结构化结果双通道；同事务持久化；确定性卡片动作；会话内实体引用；超时、重试、熔断、部分降级和灰度回退。每个关键点链接到最终类和测试，不复制易过期的大段源码。

- [ ] **Step 2: 写面试复盘，不把模型工具选择说成魔法**

按“问题—设计—取舍—故障—指标—验证”组织，能够回答：为什么不能让 Agent 直连多库、如何防止模型越权、为什么结构化结果不能只存在前端、物流部分失败怎么处理、历史引用为什么必须重新查实时状态、如何防止工具循环、如何控制上下文 Token 和敏感信息。

- [ ] **Step 3: 更新桌面学习记录**

记录本节实际覆盖的 Spring AI Tool Calling、ToolContext、结构化 SSE、ChatMemory/摘要与业务引用的边界、OpenFeign、MyBatis 批量聚合、Resilience4j、幂等与事务、Electron IPC 安全。明确第一版不做跨会话客户长期记忆；Milvus + ES RAG 阶段再补，不能把实时订单/物流写成长效客户事实。

- [ ] **Step 4: 扫描过时内容和占位符**

Run: `rg -n "order-summary|Qdrant|TO[D]O|TB[D]|[稍]后实现|[临]时方案" docs/architecture docs/interview src C:/Users/shwfo/Desktop/AI应用/Spring\ AI\ Agent项目学习记录.md`

Expected: 不存在旧占位订单契约、不存在 Qdrant（当前路线固定为 Milvus + ES）、不存在未解决占位符；命中“稍后实现”只能是明确范围边界且带对应阶段。

- [ ] **Step 5: 提交仓库文档**

```bash
git add docs/architecture/order-logistics-tool-chain.md docs/interview/order-logistics-agent-interview-notes.md
git commit -m "docs: explain order logistics agent design"
```

桌面学习记录不属于 Git 仓库，单独检查文件存在和更新时间，不加入上述提交。

---

## 最终验收清单

- [ ] 三个后端工程全量测试通过，前端测试、类型检查、Lint 和生产构建通过。
- [ ] Agent、`order`、`silu-logistics` 的功能开关和超时均来自 Nacos，真实密钥只来自环境变量。
- [ ] 无权访问与不存在不可区分，权限服务失败关闭，日志和响应无敏感字段。
- [ ] 订单查询无 N+1；物流只刷新正确承运商；批量轨迹允许部分失败。
- [ ] SSE 完整结果可即时展示并与助手消息原子持久化，重启后可恢复。
- [ ] 卡片动作绕过模型决策但不绕过后端鉴权；会话引用只解析指代并强制实时复查。
- [ ] 灰度顺序、回退开关、指标与告警写入 runbook，未达到门槛时不全量开启。
