# 聊天 SSE 断线重连与事件补发实施计划

> **供智能开发代理执行：** 必须使用 `superpowers:executing-plans` 逐项实施本计划。每一步使用复选框（`- [ ]`）跟踪；本项目明确要求在当前会话执行，不委派子代理，并将每批已验证改动直接提交到本地 `main`。

**目标：** 为聊天流实现基于 Redis Streams 的可恢复事件日志、断点补发、状态查询、显式取消，以及 Electron 端最多 5 次指数退避重连，同时不重复创建消息或调用模型。

**架构：** 可恢复模式下，Agent 工作线程只向 Redis Streams 发布有序事件，SSE 连接只负责从指定序号向客户端转发；连接断开不再取消业务任务。Redis 在业务消息创建前不可用时，单轮降级为现有直连 SSE；任务开始后 Redis 故障则安全终止并通过 MySQL 状态兜底。Electron 主进程持有 `requestId`、最后确认序号和累计重试次数，渲染进程只消费业务事件与本地传输状态。

**技术栈：** Java 21、Spring Boot MVC/SseEmitter、Spring Data Redis、Redis Streams/Lua、MySQL/MyBatis-Plus、Micrometer、Electron、TypeScript、Vue 3、Pinia、Vitest。

---

### 任务 1：扩展后端协议与可恢复流配置

**文件：**
- 修改：`src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamRequest.java`
- 修改：`src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java`
- 修改：`src/main/java/com/xjjk/agent/chat/config/ChatStreamProperties.java`
- 修改：`src/main/resources/application.properties`
- 修改：`src/test/resources/application.properties`
- 创建：`src/test/java/com/xjjk/agent/chat/config/ChatStreamPropertiesTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamRequestContractTest.java`

- [ ] **步骤 1：先写配置边界和 requestId 校验失败测试**

```java
@Test
void rejectsInvalidReplayAndReconnectConfiguration() {
    assertThatThrownBy(() -> new ChatStreamProperties(
            Duration.ofSeconds(30), Duration.ofSeconds(10),
            new Replay(true, "agent:chat:stream:v1", Duration.ofMinutes(2),
                    0, 65_536, 1_048_576, Duration.ofSeconds(5)),
            new Reconnect(5, Duration.ofMillis(500), Duration.ofSeconds(8), 0.2)))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void clientRequestIdMustBeUuid() {
    Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(
            new ChatStreamRequest(null, "你好", null, "not-a-uuid"));
    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("请求ID格式不合法");
}
```

- [ ] **步骤 2：运行测试并确认因新字段和嵌套配置不存在而失败**

运行：`mvn -Dtest=ChatStreamPropertiesTest,ChatStreamRequestContractTest test`

预期：编译失败，提示 `Replay`、`Reconnect` 或四参数 `ChatStreamRequest` 不存在。

- [ ] **步骤 3：实现配置记录、UUID 校验和 session 恢复元数据**

```java
public record ChatStreamRequest(
        @Size(max = 64, message = "会话ID不能超过64个字符") String conversationId,
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "消息内容不能超过2000个字符") String message,
        @Valid ChatActionRequest action,
        @NotBlank(message = "请求ID不能为空")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                message = "请求ID格式不合法") String clientRequestId) {
}
```

```java
public record Session(
        String conversationId,
        String requestId,
        Instant expiresAt,
        boolean resumable) {
}
```

`ChatStreamProperties` 增加经过构造器完整校验的 `Replay` 与 `Reconnect` 嵌套记录，固定约束：`maxEvents > 0`、单事件和总字节上限合理、初始退避不大于最大退避、随机抖动范围为 `[0,1)`。

- [ ] **步骤 4：加入本地默认配置**

```properties
agent.chat.stream.replay.enabled=true
agent.chat.stream.replay.key-prefix=agent:chat:stream:v1
agent.chat.stream.replay.ttl=2m
agent.chat.stream.replay.max-events=512
agent.chat.stream.replay.max-event-bytes=65536
agent.chat.stream.replay.max-stream-bytes=1048576
agent.chat.stream.replay.read-block-timeout=5s
agent.chat.stream.reconnect.max-attempts=5
agent.chat.stream.reconnect.initial-backoff=500ms
agent.chat.stream.reconnect.max-backoff=8s
agent.chat.stream.reconnect.jitter-ratio=0.2
```

- [ ] **步骤 5：运行定向测试和现有配置装配测试**

运行：`mvn -Dtest=ChatStreamPropertiesTest,ChatStreamRequestContractTest,ChatSseHeartbeatTest test`

预期：全部通过；现有心跳间隔仍必须小于 30 秒主链路截止时间。

- [ ] **步骤 6：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamRequest.java src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java src/main/java/com/xjjk/agent/chat/config/ChatStreamProperties.java src/main/resources/application.properties src/test/resources/application.properties src/test/java/com/xjjk/agent/chat/config/ChatStreamPropertiesTest.java src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamRequestContractTest.java
git commit -m "feat: define resumable chat stream protocol"
```

### 任务 2：把业务事件发布与具体 SSE 连接解耦

**文件：**
- 创建：`src/main/java/com/xjjk/agent/chat/stream/ChatEventPublisher.java`
- 创建：`src/main/java/com/xjjk/agent/chat/stream/DirectChatEventPublisher.java`
- 修改：`src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnFinalizer.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java`
- 修改：`src/test/java/com/xjjk/agent/chat/stream/ChatSseSessionTest.java`
- 修改：`src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **步骤 1：写发布端口契约测试**

```java
@Test
void directPublisherKeepsSequenceAndTerminalRules() throws Exception {
    RecordingEmitter emitter = new RecordingEmitter();
    ChatEventPublisher publisher = new DirectChatEventPublisher(emitter);
    publisher.session("conversation", "request", Instant.now().plusSeconds(30), false);
    publisher.generating();
    publisher.delta("a");
    publisher.done("message");
    assertThat(emitter.events()).extracting(ChatStreamEvent::sequence)
            .containsExactly(1L, 2L, 3L, 4L);
    assertThat(publisher.heartbeat()).isFalse();
}
```

- [ ] **步骤 2：运行测试并确认端口不存在**

运行：`mvn -Dtest=ChatSseSessionTest test`

预期：编译失败，提示 `ChatEventPublisher` 或 `DirectChatEventPublisher` 不存在。

- [ ] **步骤 3：定义不依赖网络连接的发布端口**

```java
public interface ChatEventPublisher {
    void session(String conversationId, String requestId, Instant expiresAt,
                 boolean resumable) throws IOException;
    boolean heartbeat() throws IOException;
    void generating() throws IOException;
    void queryingLogistics() throws IOException;
    void queryingCustomerOrders() throws IOException;
    void queryingAfterSaleDetail() throws IOException;
    void delta(String text) throws IOException;
    void result(ToolUiResult result) throws IOException;
    void done(String messageId) throws IOException;
    void error(ChatStreamError error, String requestId) throws IOException;
    void complete();
}
```

让 `DirectChatEventPublisher` 承担原 `ChatSseSession` 的序号、终态和 Emitter 写出职责；`ChatSseSession` 暂时作为兼容包装，避免一次性破坏现有测试。将 Runner、Finalizer 和 Gate 的参数类型全部收窄为 `ChatEventPublisher`。

- [ ] **步骤 4：运行所有流式执行测试**

运行：`mvn -Dtest='ChatSseSessionTest,ChatTurnRunner*Test,FreshBusinessResultGateTest' test`

预期：全部通过，业务执行语义不变。

- [ ] **步骤 5：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/stream src/main/java/com/xjjk/agent/chat/service/stream src/test/java/com/xjjk/agent/chat/stream src/test/java/com/xjjk/agent/chat/service/stream
git commit -m "refactor: decouple chat events from SSE connections"
```

### 任务 3：实现 Redis Streams 事件仓储和容量保护

**文件：**
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayKeyFactory.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayState.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayMetadata.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplaySnapshot.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayRepository.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/RedisChatReplayRepository.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayUnavailableException.java`
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ChatReplayLimitException.java`
- 创建：`src/main/resources/redis/chat-stream-append.lua`
- 创建：`src/test/java/com/xjjk/agent/chat/replay/ChatReplayKeyFactoryTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/replay/RedisChatReplayRepositoryTest.java`

- [ ] **步骤 1：写 Key 隔离、创建幂等和原子追加测试**

```java
@Test
void keysSeparateTenantUserAndRequest() {
    ChatReplayKeyFactory factory = new ChatReplayKeyFactory("agent:chat:stream:v1");
    assertThat(factory.events(1, 2, "request"))
            .isEqualTo("agent:chat:stream:v1:{1:2:request}:events");
}

@Test
void appendAssignsMonotonicSequenceAndRejectsAfterTerminal() {
    repository.create(metadata);
    ChatStreamEvent<?> first = repository.append(identity, requestId, "status", status);
    ChatStreamEvent<?> terminal = repository.append(identity, requestId, "done", done);
    assertThat(first.sequence()).isEqualTo(1);
    assertThat(terminal.sequence()).isEqualTo(2);
    assertThatThrownBy(() -> repository.append(identity, requestId, "delta", delta))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **步骤 2：运行测试并确认仓储类型不存在**

运行：`mvn -Dtest='ChatReplayKeyFactoryTest,RedisChatReplayRepositoryTest' test`

预期：编译失败。

- [ ] **步骤 3：实现仓储端口和 Redis Key 工厂**

```java
public interface ChatReplayRepository {
    boolean available();
    CreateResult create(ChatReplayMetadata metadata);
    ChatStreamEvent<?> append(AgentIdentity identity, String requestId,
                              String type, Object payload);
    List<ChatStreamEvent<?>> readAfter(AgentIdentity identity, String requestId,
                                       long afterSequence, Duration blockTimeout);
    Optional<ChatReplaySnapshot> status(AgentIdentity identity, String requestId);
    boolean requestCancel(AgentIdentity identity, String requestId);
    boolean cancellationRequested(AgentIdentity identity, String requestId);
}
```

`ChatReplayKeyFactory` 必须使用同一个 Redis Cluster hash tag `{tenantId:userId:requestId}` 派生 `meta`、`events`、`control` 三个 Key，使 Lua 可以原子操作。

- [ ] **步骤 4：实现 Lua 原子追加**

`chat-stream-append.lua` 必须在同一次脚本执行中：验证归属和非终态、计算 JSON UTF-8 字节、为 `done/error` 预留至少一条事件容量、递增 `lastSequence`、以 `<sequence>-0` 执行 `XADD`、更新累计字节和状态、刷新 2 分钟 TTL。达到限制返回明确错误码，不允许静默 `XTRIM`。

```lua
local state = redis.call('HGET', KEYS[1], 'state')
if not state then return {'NOT_FOUND'} end
if state == 'DONE' or state == 'ERROR' or state == 'CANCELLED' or state == 'TIMEOUT' then
  return {'TERMINAL'}
end
local next = tonumber(redis.call('HGET', KEYS[1], 'lastSequence') or '0') + 1
redis.call('XADD', KEYS[2], tostring(next) .. '-0', 'event', ARGV[1])
redis.call('HSET', KEYS[1], 'lastSequence', tostring(next), 'streamBytes', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return {'OK', tostring(next)}
```

实现时补齐脚本中的身份、容量、终态和累计字节判断；Java 端只接受白名单返回码并把 Redis 异常归一化为 `ChatReplayUnavailableException`。

- [ ] **步骤 5：验证读取严格大于 afterSequence 且不会跨用户读取**

运行：`mvn -Dtest='ChatReplayKeyFactoryTest,RedisChatReplayRepositoryTest' test`

预期：序号、终态、TTL、容量、归属、Redis 异常测试全部通过。

- [ ] **步骤 6：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/replay src/main/resources/redis src/test/java/com/xjjk/agent/chat/replay
git commit -m "feat: persist resumable chat events in Redis Streams"
```

### 任务 4：实现可恢复事件发布器和脱离连接的任务生命周期

**文件：**
- 创建：`src/main/java/com/xjjk/agent/chat/replay/ReplayChatEventPublisher.java`
- 创建：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnJob.java`
- 创建：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnJobRegistry.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnPreparationService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnStartService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnFinalizer.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeat.java`
- 创建：`src/test/java/com/xjjk/agent/chat/service/stream/ReplayChatEventPublisherTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnJobRegistryTest.java`

- [ ] **步骤 1：写“Emitter 断开不取消任务”和“显式取消才停止”的测试**

```java
@Test
void disconnectingRelayDoesNotStopDetachedJob() {
    ChatTurnJob job = registry.register(requestId, control, future);
    job.relayDisconnected();
    assertThat(control.isStopRequested()).isFalse();
}

@Test
void explicitCancelStopsOnlyOwnedRunningJob() {
    assertThat(registry.cancel(identity, requestId)).isTrue();
    assertThat(control.isStopRequested()).isTrue();
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`mvn -Dtest='ReplayChatEventPublisherTest,ChatTurnJobRegistryTest' test`

预期：编译失败。

- [ ] **步骤 3：让前端生成的 clientRequestId 成为正式 requestId**

将 `ChatTurnPreparationService.prepare` 和 `ChatTurnStartService.begin` 增加 `clientRequestId` 参数；开始事务不再调用 `UUID.randomUUID()` 生成 requestId，而是使用已由 Bean Validation 验证、且只由 Electron 主进程生成的 UUID。遇到 `uk_request_role` 冲突时，不再次执行业务；由上层根据 Redis/MySQL 状态连接已有请求。

- [ ] **步骤 4：实现 Redis 发布器**

`ReplayChatEventPublisher` 实现 `ChatEventPublisher`，所有 session/status/delta/result/heartbeat/done/error 都只调用 `ChatReplayRepository.append`。Redis 追加失败必须触发 `CHAT_STREAM_REPLAY_UNAVAILABLE`，随后统一收尾为失败；不能在任务已经开始后切换为直连模式。

- [ ] **步骤 5：实现进程内任务注册表作为取消加速层**

```java
public interface ChatTurnJobRegistry {
    boolean register(ChatTurnJob job);
    Optional<ChatTurnJob> find(String requestId);
    boolean cancel(AgentIdentity identity, String requestId);
    void remove(String requestId);
}
```

注册表不承担跨实例正确性：取消接口还必须写 Redis control；Runner 在准备前、模型迭代、工具结果发布和收尾前检查 Redis 取消标志。进程内注册表只用于立即中断本实例 `FutureTask`。

- [ ] **步骤 6：把心跳绑定到任务而不是 Emitter**

可恢复模式的心跳租约随 `ChatTurnJob` 创建并在统一收尾后关闭；直连模式保留原有连接级心跳。SSE 转发器不得再创建新的业务心跳。

- [ ] **步骤 7：运行执行器、取消和心跳测试**

运行：`mvn -Dtest='ReplayChatEventPublisherTest,ChatTurnJobRegistryTest,ChatTurnRunner*Test,ChatSseHeartbeatTest' test`

预期：全部通过；网络断开不改变 `ChatStreamControl`，显式取消和 30 秒任务超时会改变控制状态。

- [ ] **步骤 8：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat src/test/java/com/xjjk/agent/chat
git commit -m "feat: detach chat jobs from SSE connections"
```

### 任务 5：增加 start、resume、status、cancel 接口与 SSE 转发器

**文件：**
- 创建：`src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamStatusResponse.java`
- 创建：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseRelay.java`
- 创建：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseRelayService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/api/controller/ChatStreamController.java`
- 修改：`src/main/java/com/xjjk/agent/chat/config/ChatStreamConfiguration.java`
- 创建：`src/test/java/com/xjjk/agent/chat/api/controller/ChatStreamControllerTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/service/stream/ChatSseRelayServiceTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/service/stream/ChatStreamServiceTest.java`

- [ ] **步骤 1：写接口归属、补发边界和断连语义测试**

```java
@Test
void resumeOnlyRelaysEventsAfterConfirmedSequence() throws Exception {
    SseEmitter emitter = service.resume(identity, requestId, 7);
    verify(repository).readAfter(identity, requestId, 7L, Duration.ofSeconds(5));
    assertThat(recordedSequences(emitter)).doesNotContain(7L);
}

@Test
void relayDisconnectDoesNotCancelProducer() {
    relay.onCompletion();
    verifyNoInteractions(control);
}

@Test
void foreignRequestIsNotDisclosed() {
    assertThatThrownBy(() -> service.status(otherIdentity, requestId))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`mvn -Dtest='ChatStreamControllerTest,ChatSseRelayServiceTest,ChatStreamServiceTest' test`

预期：编译失败。

- [ ] **步骤 3：实现四个接口**

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request,
                         @CurrentAgentIdentity AgentIdentity identity,
                         HttpServletResponse response) { ... }

@GetMapping(value = "/stream/{requestId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter resume(@PathVariable String requestId,
                         @RequestParam long afterSequence,
                         @CurrentAgentIdentity AgentIdentity identity,
                         HttpServletResponse response) { ... }

@GetMapping("/stream/{requestId}/status")
public ChatStreamStatusResponse status(@PathVariable String requestId,
                                       @CurrentAgentIdentity AgentIdentity identity) { ... }

@PostMapping("/stream/{requestId}/cancel")
public void cancel(@PathVariable String requestId,
                   @CurrentAgentIdentity AgentIdentity identity) { ... }
```

四个接口都必须校验 UUID 和认证归属；不得接受 tenantId/userId 请求参数。

- [ ] **步骤 4：实现响应头和单连接替换**

初始和恢复响应都返回：`X-Chat-Request-Id`、`X-Chat-Expires-At`、`X-Chat-Resumable`、四个重连策略响应头。Redis meta 中以新的 `activeConnectionId` 原子替换旧连接；旧转发器下一次阻塞读取返回后发现连接 ID 不一致即正常退出，不影响任务。

- [ ] **步骤 5：实现直连降级边界**

`ChatStreamService.start` 在创建业务消息前探测并初始化 Redis：成功则创建脱离连接的 Job 和 Relay；失败则创建 `DirectChatEventPublisher`，session 标记 `resumable=false`，保留断连即取消的原行为。降级分支必须使用同一个 clientRequestId，并通过响应头明确禁止 Electron 自动重连。

- [ ] **步骤 6：实现 30 秒绝对截止时间**

任务截止时间在首次 POST 接入时固定为 `createdAt + timeout`，写入 Redis meta，并由独立计划任务触发 `MessageStatus.TIMEOUT`。恢复连接只读取这个时间，不创建或刷新任务定时器。

- [ ] **步骤 7：运行控制器和服务测试**

运行：`mvn -Dtest='ChatStreamControllerTest,ChatSseRelayServiceTest,ChatStreamServiceTest,ChatTurnRunner*Test' test`

预期：全部通过；裸断连后任务继续，resume 只补发缺失序号，cancel 终止任务，status 能从 Redis 或 MySQL 给出可信终态。

- [ ] **步骤 8：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat src/test/java/com/xjjk/agent/chat
git commit -m "feat: add chat stream resume status and cancel APIs"
```

### 任务 6：补充可观测性、Redis 故障策略和后端回归

**文件：**
- 创建：`src/main/java/com/xjjk/agent/chat/observation/ChatStreamReplayMetrics.java`
- 修改：`src/main/java/com/xjjk/agent/chat/replay/RedisChatReplayRepository.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseRelayService.java`
- 创建：`src/test/java/com/xjjk/agent/chat/observation/ChatStreamReplayMetricsTest.java`
- 创建：`src/test/java/com/xjjk/agent/chat/service/stream/ChatStreamFailurePolicyTest.java`

- [ ] **步骤 1：写故障分类和指标测试**

```java
@Test
void redisUnavailableBeforeStartFallsBackButMidTurnFailureTerminatesSafely() {
    when(repository.available()).thenReturn(false);
    assertThat(service.start(request, identity).resumable()).isFalse();

    when(repository.available()).thenReturn(true);
    doThrow(new ChatReplayUnavailableException()).when(repository)
            .append(any(), anyString(), eq("delta"), any());
    assertThat(runTurn()).hasTerminalCode("CHAT_STREAM_REPLAY_UNAVAILABLE");
}
```

- [ ] **步骤 2：实现低基数指标**

至少记录：`chat.stream.mode{mode=resumable|direct}`、`chat.stream.resume.attempt{result}`、`chat.stream.replay.events`、`chat.stream.replay.bytes`、`chat.stream.replay.failure{reason}`、`chat.stream.cancel{result}`、`chat.stream.relay.connections`。标签不得包含 tenantId、userId、conversationId 或 requestId。

- [ ] **步骤 3：运行后端全量测试**

运行：`mvn test`

预期：所有测试通过；无新增失败和 Surefire error。

- [ ] **步骤 4：提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/observation src/main/java/com/xjjk/agent/chat/replay src/main/java/com/xjjk/agent/chat/service/stream src/test/java/com/xjjk/agent/chat
git commit -m "feat: observe resumable chat stream failures"
```

### 任务 7：扩展 Electron 主进程共享协议

**文件：**
- 修改：`D:/GitCode/order-logistics-agent-web/src/shared/desktop.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/preload/index.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/main/ipc.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/main/ipc.test.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/contracts/chat.ts`

- [ ] **步骤 1：写 IPC 传输状态和显式取消契约测试**

```ts
it('exposes transport state separately from server events', () => {
  const result: ChatStreamReadResult = {
    done: false,
    transport: { type: 'reconnecting', attempt: 2, maxAttempts: 5 }
  }
  expect(result.transport.type).toBe('reconnecting')
})
```

- [ ] **步骤 2：运行测试并确认联合类型尚不支持 transport**

运行：`npm test -- src/main/ipc.test.ts`

预期：TypeScript 编译或断言失败。

- [ ] **步骤 3：定义共享联合类型**

```ts
export type ChatStreamTransportState =
  | { type: 'reconnecting'; attempt: number; maxAttempts: number; delayMs: number }
  | { type: 'reconnected'; attempt: number; maxAttempts: number }
  | { type: 'reconnect-exhausted'; requestId: string; message: string }

export type ChatStreamReadResult =
  | { done: true; event?: never; transport?: never }
  | { done: false; event: unknown; transport?: never }
  | { done: false; event?: never; transport: ChatStreamTransportState }
```

`ChatStreamRequest` 增加仅供主进程内部使用的 `clientRequestId`，renderer 的 `ChatRequest` 不增加该字段；IPC handler 必须忽略渲染进程伪造的 clientRequestId，由 `AgentChatStreamClient` 自己生成。

- [ ] **步骤 4：运行 IPC 与类型检查**

运行：`npm test -- src/main/ipc.test.ts && npm run typecheck`

预期：全部通过。

- [ ] **步骤 5：提交 Electron 改动**

```powershell
Set-Location 'D:\GitCode\order-logistics-agent-web'
git add src/shared/desktop.ts src/preload/index.ts src/main/ipc.ts src/main/ipc.test.ts src/renderer/src/contracts/chat.ts
git commit -m "feat: define chat reconnect transport states"
```

### 任务 8：实现 Electron 主进程指数退避、断点续拉和显式取消

**文件：**
- 创建：`D:/GitCode/order-logistics-agent-web/src/main/chat-reconnect-policy.ts`
- 创建：`D:/GitCode/order-logistics-agent-web/src/main/chat-reconnect-policy.test.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.test.ts`

- [ ] **步骤 1：写 0.5/1/2/4/8 秒、±20% 抖动和累计 5 次测试**

```ts
it('uses bounded exponential backoff with jitter', () => {
  const policy = new ChatReconnectPolicy(5, 500, 8_000, 0.2, () => 0.5)
  expect([1, 2, 3, 4, 5].map((attempt) => policy.delay(attempt)))
    .toEqual([500, 1_000, 2_000, 4_000, 8_000])
  expect(policy.canRetry(5)).toBe(false)
})
```

- [ ] **步骤 2：写断点续拉、去重、裸 EOF 和显式停止测试**

```ts
it('resumes after the last accepted sequence without replaying POST', async () => {
  const streamId = await client.start({ message: '你好' }, ownerId)
  await client.next(streamId, ownerId) // session seq=1
  network.breakCurrentResponse()
  await client.next(streamId, ownerId) // reconnecting transport state
  await client.next(streamId, ownerId) // resumed seq=2
  expect(fetchCalls.filter((call) => call.method === 'POST')).toHaveLength(1)
  expect(resumeUrl).toContain('afterSequence=1')
})
```

- [ ] **步骤 3：运行测试并确认失败**

运行：`npm test -- src/main/chat-reconnect-policy.test.ts src/main/agent-chat-stream-client.test.ts`

预期：新测试失败。

- [ ] **步骤 4：扩展 ActiveStream 为整轮状态机**

`ActiveStream` 增加 `requestId`、`expiresAt`、`resumable`、`lastAcceptedSequence`、`retryAttempts`、`retryPolicy`、`terminalSeen`、`unresolved`。首次 POST 前在主进程生成 UUID 并写入 body；从响应头校验 requestId 和服务器策略，不能盲信渲染进程或事件正文覆盖。

- [ ] **步骤 5：实现重连分类和序号幂等**

仅对网络异常、TCP reset、裸 EOF、25 秒空闲超时，以及 resume/status 的 408/429/502/503/504 重试。收到已确认序号及更早事件时丢弃；发现序号跳跃时继续从最后连续序号恢复，不得直接渲染后续事件。401 退出登录；400/403/404/410、非法 SSE、超限和服务端 `error` 不重试。

- [ ] **步骤 6：实现累计重试和传输状态输出**

每次异常恢复前先把 `reconnecting` 作为本地传输状态返回 IPC，再等待带抖动的退避并调用 resume。成功连接不清零累计次数；成功读到下一条有效事件后返回 `reconnected`。达到 5 次后返回 `reconnect-exhausted`，保留 requestId 供下一次发送前状态核对。

- [ ] **步骤 7：实现显式 cancel API**

`abort(streamId)` 对已知 requestId 先调用 `POST /api/v1/chat/stream/{requestId}/cancel`，再关闭本地 reader；网络断开恢复路径只能关闭当前 reader，禁止调用 cancel。取消 API 失败时仍结束本地 UI，但记录诊断日志，后端由 30 秒截止时间兜底。

- [ ] **步骤 8：运行主进程测试与类型检查**

运行：`npm test -- src/main/chat-reconnect-policy.test.ts src/main/agent-chat-stream-client.test.ts src/main/ipc.test.ts && npm run typecheck`

预期：全部通过；自动重连没有第二次 POST，显式停止恰好调用一次 cancel。

- [ ] **步骤 9：提交**

```powershell
git add src/main/chat-reconnect-policy.ts src/main/chat-reconnect-policy.test.ts src/main/agent-chat-stream-client.ts src/main/agent-chat-stream-client.test.ts src/main/ipc.ts src/main/ipc.test.ts src/shared/desktop.ts
git commit -m "feat: reconnect interrupted chat streams"
```

### 任务 9：渲染层展示恢复状态、锁定输入并在下次发送前核对旧请求

**文件：**
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/chat-transport.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.test.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-store-contract.test.ts`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.vue`
- 修改：`D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.test.ts`

- [ ] **步骤 1：写恢复期间锁定和达到上限后解锁测试**

```ts
it('keeps the composer locked while reconnecting and unlocks after exhaustion', async () => {
  transport.pushState({ type: 'reconnecting', attempt: 1, maxAttempts: 5, delayMs: 500 })
  expect(store.isBusy).toBe(true)
  expect(store.statusText).toBe('连接中断，正在恢复（1/5）')
  transport.pushState({ type: 'reconnect-exhausted', requestId, message: '连接恢复失败，请稍后重试' })
  await pending
  expect(store.isBusy).toBe(false)
  expect(store.statusText).toBe('连接恢复失败，请稍后重试')
})
```

- [ ] **步骤 2：写下次发送前状态核对和输入保留测试**

```ts
it('does not append or clear a new message while an unresolved turn is still running', async () => {
  statusClient.reply({ state: 'RUNNING' })
  const before = [...store.messages]
  expect(await store.send('下一条')).toEqual({ accepted: false, preserveInput: true })
  expect(store.messages).toEqual(before)
  expect(store.statusText).toBe('上一轮仍在处理中')
})
```

- [ ] **步骤 3：运行渲染层测试并确认失败**

运行：`npm test -- src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/stores/chat-store-contract.test.ts src/renderer/src/components/ChatWindow.test.ts`

预期：新测试失败。

- [ ] **步骤 4：让 transport 同时产出业务事件与本地状态**

```ts
export type ChatTransportItem =
  | { kind: 'event'; event: ChatEvent }
  | { kind: 'transport'; state: ChatStreamTransportState }

export interface ChatTransport {
  stream(request: ChatRequest, signal: AbortSignal): AsyncIterable<ChatTransportItem>
}
```

`IpcChatTransport` 仅对 `kind: 'event'` 做 SSE 协议校验和诊断日志；本地重连状态不伪造服务器 sequence。

- [ ] **步骤 5：更新 Store 状态机**

恢复期间保持同一个 `activeController`，因此 `isStreaming/isBusy` 继续为真，输入框、新对话、会话切换和卡片动作均被锁定。达到上限时把当前助手消息标为不完整、保存 unresolved requestId、清理 controller 并解锁。

下一次普通发送或卡片动作必须先调用主进程状态核对：`DONE` 时刷新当前会话历史并继续；明确失败终态时清理 unresolved 并继续；`RUNNING` 时拒绝新请求；状态接口网络失败时拒绝并保留输入。

- [ ] **步骤 6：修正 ChatWindow 清空输入的时机**

```ts
async function send(): Promise<void> {
  const content = input.value.trim()
  if (!content || chat.isBusy) return
  const result = await chat.send(content)
  if (result.accepted) input.value = ''
  await nextTick()
  scrollToBottom()
}
```

这样状态预检拒绝时不会丢失用户已经输入的内容；真正接受请求后才清空。

- [ ] **步骤 7：运行渲染层测试和类型检查**

运行：`npm test -- src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/stores/chat-store-contract.test.ts src/renderer/src/components/ChatWindow.test.ts && npm run typecheck`

预期：全部通过；恢复期间输入框禁用，失败上限后明确提示并解锁，预检失败保留输入。

- [ ] **步骤 8：提交**

```powershell
git add src/renderer/src/transports src/renderer/src/stores/chat.ts src/renderer/src/stores/chat-store-contract.test.ts src/renderer/src/components/ChatWindow.vue src/renderer/src/components/ChatWindow.test.ts
git commit -m "feat: surface chat reconnect state in the UI"
```

### 任务 10：网关配置说明、全量验证与桌面包验收

**文件：**
- 修改：`docs/superpowers/specs/2026-09-14-chat-stream-reconnection-design.md`
- 创建：`docs/operations/chat-stream-reconnection.md`
- 修改：`D:/GitCode/order-logistics-agent-web/README.md`

- [ ] **步骤 1：写中文运维说明**

文档必须给出 Nacos 完整配置项、Redis 容量估算、告警指标和网关要求。Nginx 示例：

```nginx
location /api/v1/chat/stream {
    proxy_http_version 1.1;
    proxy_buffering off;
    gzip off;
    proxy_read_timeout 45s;
    proxy_send_timeout 45s;
}
```

明确 45 秒网关超时只是覆盖 30 秒主链路加收尾余量，不是重连总时长；网关不得自动重放 POST。

- [ ] **步骤 2：运行后端全量验证**

运行：`mvn test`

预期：BUILD SUCCESS，所有后端测试通过。

- [ ] **步骤 3：运行 Electron 全量验证**

运行：`npm test`

预期：所有 Vitest 测试文件和测试用例通过。

运行：`npm run typecheck`

预期：主进程和渲染进程 TypeScript 检查均通过。

- [ ] **步骤 4：确认未覆盖用户已有工作区改动**

运行：`git status --short`

预期：以下既有项目仍保持原状，不被本功能暂存或修改：

```text
 M src/renderer/src/components/LogisticsTimelineCard.test.ts
?? dist-verify/
?? src/renderer/src/components/order-card-view.test.ts
```

- [ ] **步骤 5：重新打包 Electron**

运行：`npm run build:win`

预期：生成 `D:\GitCode\order-logistics-agent-web\dist\win-unpacked\enterprise-customer-service-assistant.exe`。

- [ ] **步骤 6：进行真实断网验收**

使用诊断模式打开桌面程序，发送一条能持续超过 10 秒的请求；在收到至少一个 delta 后临时断开网络，再恢复网络。控制台和界面必须观察到：

```text
连接中断，正在恢复（1/5）
reconnected
后续事件 sequence 严格递增且没有重复 delta
done 或明确 error 终态
```

再次测试连续断网直至 5 次失败：输入框在恢复期间锁定，达到上限后显示“连接恢复失败，请稍后重试”并解锁；点击停止时调用 cancel，不能进入自动重连。

- [ ] **步骤 7：提交文档和最终必要改动**

```powershell
Set-Location 'D:\GitCode\order-logistics-agent-server'
git add docs/superpowers/specs/2026-09-14-chat-stream-reconnection-design.md docs/operations/chat-stream-reconnection.md
git commit -m "docs: document chat stream recovery operations"

Set-Location 'D:\GitCode\order-logistics-agent-web'
git add README.md
git commit -m "docs: document desktop chat recovery diagnostics"
```

## 自检结果

- 规格覆盖：计划已覆盖 Redis Streams、任务与连接解耦、30 秒绝对截止时间、2 分钟补发 TTL、5 次累计重试、指数退避与 ±20% 抖动、输入锁定、最大次数提示、显式取消、状态预检、Redis 启动前降级、任务中 Redis 故障、归属校验、容量保护、指标、网关和桌面包验收。
- 占位符检查：计划没有 `TBD`、`TODO`、“稍后实现”或未定义的模糊步骤；省略号只出现在已给出完整职责的控制器方法体示意中，实际行为由同任务后续步骤和测试明确约束。
- 类型一致性：后端统一使用 `clientRequestId`、`requestId`、`afterSequence`、`ChatEventPublisher`；Electron 统一使用 `ChatStreamTransportState`、`ChatTransportItem` 和 `unresolved requestId`，与设计文档一致。
