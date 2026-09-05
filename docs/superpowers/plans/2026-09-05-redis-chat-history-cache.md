# Redis Chat History Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为稳定会话历史增加版本化 Redis 快照缓存、MySQL 自动回源和事务提交后异步预热，同时保证 Redis 故障不影响正常对话。

**Architecture:** MySQL 始终负责身份、占用和稳定游标校验；事务外的 `ChatHistorySnapshotProvider` 根据游标读取版本化 Redis Key，未命中时调用数据库加载器并补写缓存。收尾和恢复事务发布历史变更事件，`AFTER_COMMIT` 监听器通过独立有界线程池预热新版本；Redis 的任何失败均 Fail-Open 回源 MySQL。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Data Redis、Jackson、MyBatis-Plus、Spring Transaction Events、Micrometer、JUnit 5、Mockito

---

## File Map

**Create production files:**

- `src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheProperties.java`：缓存开关、Key、TTL、抖动和预热线程池配置。
- `src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheConfiguration.java`：预热专用有界线程池。
- `src/main/java/com/xjjk/agent/chat/domain/memory/ChatHistoryCursor.java`：经过 MySQL 验证的稳定历史游标。
- `src/main/java/com/xjjk/agent/chat/cache/CachedChatHistorySnapshot.java`：显式版本的缓存 JSON DTO。
- `src/main/java/com/xjjk/agent/chat/cache/ChatHistoryCacheKeyFactory.java`：生成身份、版本和策略隔离的 Key。
- `src/main/java/com/xjjk/agent/chat/cache/ChatHistorySnapshotCache.java`：缓存端口。
- `src/main/java/com/xjjk/agent/chat/cache/RedisChatHistorySnapshotCache.java`：Fail-Open Redis 适配器。
- `src/main/java/com/xjjk/agent/chat/observation/ChatHistoryCacheMetrics.java`：命中、未命中、异常、非法值、写入和预热指标。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCursorLoader.java`：短事务读取并验证稳定游标。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProvider.java`：事务外编排缓存和 MySQL。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryChangedEvent.java`：事务提交后预热所需的最小事件。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListener.java`：提交后异步预热入口。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmService.java`：校验事件版本并加载、写入快照。

**Modify production files:**

- `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryLoader.java`：只按已验证游标读取 MySQL 历史正文。
- `src/main/java/com/xjjk/agent/chat/service/memory/ChatContextPreparationService.java`：改为依赖统一快照提供器。
- `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java`：事务内发布历史变更事件。
- `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnRecoveryService.java`：恢复提交后触发同样的预热流程。
- `src/main/resources/application.properties`：只增加无敏感信息的缓存配置入口；运行值仍由 Nacos 提供。

**Create tests:**

- `src/test/java/com/xjjk/agent/chat/cache/ChatHistoryCacheKeyFactoryTest.java`
- `src/test/java/com/xjjk/agent/chat/cache/RedisChatHistorySnapshotCacheTest.java`
- `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProviderTest.java`
- `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmServiceTest.java`
- `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListenerTest.java`

按照当前仓库变更规则，测试代码用于本地验证但不加入 Git 暂存区；生产 Java 文件和 `pom.xml` 变更需要暂存，XML 测试文件和迁移文件不暂存。

### Task 1: 定义缓存配置、稳定游标与 Key

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheProperties.java`
- Create: `src/main/java/com/xjjk/agent/chat/domain/memory/ChatHistoryCursor.java`
- Create: `src/main/java/com/xjjk/agent/chat/cache/ChatHistoryCacheKeyFactory.java`
- Test: `src/test/java/com/xjjk/agent/chat/cache/ChatHistoryCacheKeyFactoryTest.java`

- [ ] **Step 1: 写 Key 隔离和配置校验的失败测试**

```java
@Test
void keyContainsIdentityCursorAndPolicy() {
    ChatHistoryCacheProperties properties = new ChatHistoryCacheProperties(
            true, "agent:chat:history:v1", Duration.ofMinutes(30),
            Duration.ofMinutes(5), 1, 2, 100);
    ChatHistoryCacheKeyFactory factory = new ChatHistoryCacheKeyFactory(
            properties, new ChatHistoryProperties(200, 1_048_576));
    ChatHistoryCursor cursor = new ChatHistoryCursor(
            1, 10567, "conversation-1", 8, 16, 17);

    assertThat(factory.create(cursor)).isEqualTo(
            "agent:chat:history:v1:1:10567:conversation-1:8:16:m200-b1048576");
}

@Test
void cursorRejectsDiscontinuousBoundary() {
    assertThatThrownBy(() -> new ChatHistoryCursor(
            1, 10567, "conversation-1", 8, 16, 18))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不连续");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn -Dtest=ChatHistoryCacheKeyFactoryTest test
```

Expected: compilation failure because the three production types do not exist.

- [ ] **Step 3: 实现不可变配置、游标和 Key 工厂**

`ChatHistoryCursor` must enforce:

```java
public record ChatHistoryCursor(
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence,
        long beforeSequence
) {
    public ChatHistoryCursor {
        if (tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)
                || memoryVersion < 0 || memoryUntilSequence < 0
                || beforeSequence != Math.addExact(memoryUntilSequence, 1L)) {
            throw new IllegalArgumentException("稳定历史游标不合法或边界不连续");
        }
    }
}
```

`ChatHistoryCacheProperties` must validate `ttl > 0`, `0 <= ttlJitter < ttl`, positive pool sizes, `maxPoolSize >= corePoolSize`, positive queue capacity, and a nonblank prefix without whitespace.

`ChatHistoryCacheKeyFactory#create` must produce exactly:

```java
return properties.keyPrefix()
        + ":" + cursor.tenantId()
        + ":" + cursor.userId()
        + ":" + cursor.conversationId()
        + ":" + cursor.memoryVersion()
        + ":" + cursor.memoryUntilSequence()
        + ":m" + historyProperties.maxScanMessages()
        + "-b" + historyProperties.maxReadBytes();
```

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `mvn -Dtest=ChatHistoryCacheKeyFactoryTest test`

Expected: both tests PASS.

- [ ] **Step 5: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheProperties.java `
        src/main/java/com/xjjk/agent/chat/domain/memory/ChatHistoryCursor.java `
        src/main/java/com/xjjk/agent/chat/cache/ChatHistoryCacheKeyFactory.java
git commit -m "feat: define chat history cache contract"
```

### Task 2: 实现缓存 DTO 与 Fail-Open Redis 适配器

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/cache/CachedChatHistorySnapshot.java`
- Create: `src/main/java/com/xjjk/agent/chat/cache/ChatHistorySnapshotCache.java`
- Create: `src/main/java/com/xjjk/agent/chat/cache/RedisChatHistorySnapshotCache.java`
- Create: `src/main/java/com/xjjk/agent/chat/observation/ChatHistoryCacheMetrics.java`
- Test: `src/test/java/com/xjjk/agent/chat/cache/RedisChatHistorySnapshotCacheTest.java`

- [ ] **Step 1: 写命中、故障降级和坏值拒绝测试**

```java
@Test
void redisReadFailureBecomesCacheMiss() {
    when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

    assertThat(cache.get(cursor)).isEmpty();
    assertThat(meterRegistry.get("chat.history.cache.error").counter().count())
            .isEqualTo(1.0);
}

@Test
void mismatchedVersionIsRejected() throws Exception {
    CachedChatHistorySnapshot invalid = fixture(7, 16);
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(keyFactory.create(cursor)))
            .thenReturn(objectMapper.writeValueAsString(invalid));

    assertThat(cache.get(cursor)).isEmpty();
    assertThat(meterRegistry.get("chat.history.cache.invalid").counter().count())
            .isEqualTo(1.0);
}

@Test
void writeFailureDoesNotEscape() {
    when(redis.opsForValue()).thenReturn(valueOperations);
    doThrow(new RedisConnectionFailureException("down"))
            .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

    assertThatCode(() -> cache.put(cursor, snapshot)).doesNotThrowAnyException();
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -Dtest=RedisChatHistorySnapshotCacheTest test`

Expected: compilation failure because cache adapter types do not exist.

- [ ] **Step 3: 实现显式 DTO 和缓存端口**

The cache port must remain infrastructure-neutral:

```java
public interface ChatHistorySnapshotCache {
    Optional<ChatHistorySnapshot> get(ChatHistoryCursor cursor);
    void put(ChatHistoryCursor cursor, ChatHistorySnapshot snapshot);
}
```

`CachedChatHistorySnapshot` must contain `schemaVersion=1`, identity, cursor, flags and nested cached turns. `fromDomain` performs a defensive copy. `toDomain` reconstructs `ChatHistoryTurn` and `ChatHistorySnapshot`, so existing domain invariants are executed after deserialization.

- [ ] **Step 4: 实现 Redis 读写、内容限制和指标**

`RedisChatHistorySnapshotCache#get` must follow this exact control flow:

```java
if (!properties.enabled()) {
    return Optional.empty();
}
try {
    String json = redis.opsForValue().get(keyFactory.create(cursor));
    if (json == null) {
        metrics.miss();
        return Optional.empty();
    }
    ChatHistorySnapshot snapshot = objectMapper.readValue(
            json, CachedChatHistorySnapshot.class).toDomain();
    validate(cursor, snapshot);
    metrics.hit();
    return Optional.of(snapshot);
} catch (CacheValueInvalidException | JsonProcessingException exception) {
    metrics.invalid();
    log.warn("chat_history_cache_invalid conversationId={}, memoryVersion={}",
            cursor.conversationId(), cursor.memoryVersion());
    return Optional.empty();
} catch (RuntimeException exception) {
    metrics.error();
    log.warn("chat_history_cache_read_failed conversationId={}, exceptionType={}",
            cursor.conversationId(), exception.getClass().getSimpleName());
    return Optional.empty();
}
```

Validation must compare all identity and cursor fields, reject more than `maxScanMessages / 2` turns, and reject UTF-8 content totals above `maxReadBytes`. `put` must validate before serialization, use `ttl + random(0..ttlJitter)`, catch all Redis/Jackson runtime failures, and never log content.

`ChatHistoryCacheMetrics` must use Micrometer counters named:

```text
chat.history.cache.hit
chat.history.cache.miss
chat.history.cache.error
chat.history.cache.invalid
chat.history.cache.write.success
chat.history.cache.write.error
```

It must also expose timers named `chat.history.cache.read.duration`,
`chat.history.cache.database.load.duration`, and
`chat.history.cache.warm.duration`. The Redis adapter records read time,
the provider records MySQL fallback time, and the warm service records the
complete warm attempt. Timers must be stopped in `finally` blocks so failures
remain observable.

- [ ] **Step 5: 运行测试并确认 GREEN**

Run: `mvn -Dtest=RedisChatHistorySnapshotCacheTest test`

Expected: hit, miss, invalid payload, Redis failure and write failure tests PASS.

- [ ] **Step 6: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/cache `
        src/main/java/com/xjjk/agent/chat/observation/ChatHistoryCacheMetrics.java
git commit -m "feat: add fail-open Redis history cache"
```

### Task 3: 拆分 MySQL 游标验证与历史正文加载

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCursorLoader.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryLoader.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProviderTest.java`

- [ ] **Step 1: 写游标与加载器边界测试**

Tests must prove:

```java
@Test
void cursorLoaderRejectsDifferentActiveRequest() {
    conversation.setActiveRequestId("other-request");
    when(conversationMapper.selectOne(any())).thenReturn(conversation);

    assertThatThrownBy(() -> cursorLoader.loadForRequest(turn))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ApiErrorCode.CHAT_REQUEST_INACTIVE);
}

@Test
void cursorLoaderRejectsCurrentMessageOutsideStableBoundary() {
    conversation.setMemoryUntilSequence(8L);
    currentMessage.setMessageSequence(11L);

    assertThatThrownBy(() -> cursorLoader.loadForRequest(turn))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不连续");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -Dtest=ChatHistorySnapshotProviderTest test`

Expected: compilation failure because `ChatHistoryCursorLoader` does not exist.

- [ ] **Step 3: 提取短事务游标加载器**

Move the conversation ownership, active request, current user message and stable boundary checks from `ChatHistoryLoader` into:

```java
@Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        readOnly = true,
        timeout = 5,
        rollbackFor = Exception.class)
public ChatHistoryCursor loadForRequest(ChatTurnContext turn)
```

Add a second method used by warming:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true,
        timeout = 5, rollbackFor = Exception.class)
public Optional<ChatHistoryCursor> loadForWarm(ChatHistoryChangedEvent event)
```

`loadForWarm` must query by tenant, user and conversation, return empty when the row is absent or the database version is higher than the event, throw on a database version lower than the event, and return a cursor only when version and boundary exactly match. It does not require an active request.

- [ ] **Step 4: 改造数据库历史加载器**

Change the public API to:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ, readOnly = true,
        timeout = 5, rollbackFor = Exception.class)
public ChatHistorySnapshot load(ChatHistoryCursor cursor)
```

All metadata and content queries must use `cursor.tenantId()`, `cursor.userId()`, `cursor.conversationId()` and `cursor.beforeSequence()`. Remove conversation and current-message lookups from this class. Construct the snapshot with the cursor values and retain all current byte-budget and complete-turn checks.

- [ ] **Step 5: 运行测试并确认 GREEN**

Run: `mvn -Dtest=ChatHistorySnapshotProviderTest test`

Expected: ownership, active request, boundary, warm-event and stable-content tests PASS.

- [ ] **Step 6: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCursorLoader.java `
        src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryLoader.java
git commit -m "refactor: separate history cursor validation"
```

### Task 4: 接入统一快照提供器

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProvider.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/memory/ChatContextPreparationService.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProviderTest.java`

- [ ] **Step 1: 写命中、未命中和缓存故障编排测试**

```java
@Test
void hitSkipsDatabaseHistoryBodyLoad() {
    when(cursorLoader.loadForRequest(turn)).thenReturn(cursor);
    when(cache.get(cursor)).thenReturn(Optional.of(snapshot));

    assertThat(provider.load(turn)).isSameAs(snapshot);
    verifyNoInteractions(historyLoader);
}

@Test
void missLoadsDatabaseAndPopulatesCache() {
    when(cursorLoader.loadForRequest(turn)).thenReturn(cursor);
    when(cache.get(cursor)).thenReturn(Optional.empty());
    when(historyLoader.load(cursor)).thenReturn(snapshot);

    assertThat(provider.load(turn)).isSameAs(snapshot);
    verify(cache).put(cursor, snapshot);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -Dtest=ChatHistorySnapshotProviderTest test`

Expected: compilation failure because `ChatHistorySnapshotProvider` does not exist.

- [ ] **Step 3: 实现事务外提供器**

```java
@Service
@RequiredArgsConstructor
public class ChatHistorySnapshotProvider {
    private final ChatHistoryCursorLoader cursorLoader;
    private final ChatHistorySnapshotCache cache;
    private final ChatHistoryLoader historyLoader;

    public ChatHistorySnapshot load(ChatTurnContext turn) {
        ChatHistoryCursor cursor = cursorLoader.loadForRequest(turn);
        return cache.get(cursor).orElseGet(() -> {
            ChatHistorySnapshot snapshot = historyLoader.load(cursor);
            cache.put(cursor, snapshot);
            return snapshot;
        });
    }
}
```

No method in this provider may be transactional; Redis access must happen after the cursor loader transaction returns.

- [ ] **Step 4: 修改上下文准备入口**

Replace `ChatHistoryLoader` with `ChatHistorySnapshotProvider`. Keep the existing `BusinessException` passthrough, safe warning and `CHAT_HISTORY_LOAD_FAILED` mapping. Rename the private method to `loadSnapshot` and call `snapshotProvider.load(turn)`.

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
mvn -Dtest=ChatHistorySnapshotProviderTest test
mvn -DskipTests compile
```

Expected: provider tests PASS and main compilation succeeds.

- [ ] **Step 6: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/service/memory/ChatHistorySnapshotProvider.java `
        src/main/java/com/xjjk/agent/chat/service/memory/ChatContextPreparationService.java
git commit -m "feat: use cached history snapshot provider"
```

### Task 5: 发布提交后历史变更事件

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryChangedEvent.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnRecoveryService.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListenerTest.java`

- [ ] **Step 1: 写事务提交与回滚事件测试**

Use a Spring transaction test with a recording listener and assert:

```java
assertThat(finishService.finish(turn, MessageStatus.SUCCESS,
        "answer", "STOP", null)).isTrue();
assertThat(events).singleElement().satisfies(event -> {
    assertThat(event.memoryVersion()).isEqualTo(9);
    assertThat(event.memoryUntilSequence()).isEqualTo(18);
});
```

Force the message update to fail and assert that an `AFTER_COMMIT` listener receives no event. Add the same successful assertion for `recoverExpired`.

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -Dtest=ChatHistoryCacheWarmListenerTest test`

Expected: no event is observed because publishing is not implemented.

- [ ] **Step 3: 定义安全事件**

```java
public record ChatHistoryChangedEvent(
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence
) {
    public ChatHistoryChangedEvent {
        new ChatHistoryCursor(tenantId, userId, conversationId,
                memoryVersion, memoryUntilSequence,
                Math.addExact(memoryUntilSequence, 1L));
    }
}
```

- [ ] **Step 4: 在两个事务服务内发布事件**

Inject `ApplicationEventPublisher`. Publish only after both database updates return exactly one row:

```java
eventPublisher.publishEvent(new ChatHistoryChangedEvent(
        turn.tenantId(), turn.userId(), turn.conversationId(),
        nextMemoryVersion, nextMemoryUntilSequence));
```

Recovery uses identity and `activeRequestId`-validated conversation values. Publishing remains inside the transaction; the listener decides execution phase.

- [ ] **Step 5: 运行测试并确认 GREEN**

Run: `mvn -Dtest=ChatHistoryCacheWarmListenerTest test`

Expected: committed finish and recovery produce one event; rollback produces none at `AFTER_COMMIT`.

- [ ] **Step 6: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryChangedEvent.java `
        src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java `
        src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnRecoveryService.java
git commit -m "feat: publish stable history changes"
```

### Task 6: 实现有界异步预热

**Files:**

- Create: `src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheConfiguration.java`
- Create: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmService.java`
- Create: `src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListener.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmServiceTest.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListenerTest.java`

- [ ] **Step 1: 写预热、过时事件和队列拒绝测试**

```java
@Test
void matchingEventWarmsVersionedCache() {
    when(cursorLoader.loadForWarm(event)).thenReturn(Optional.of(cursor));
    when(historyLoader.load(cursor)).thenReturn(snapshot);

    warmService.warm(event);

    verify(cache).put(cursor, snapshot);
}

@Test
void staleEventIsSkipped() {
    when(cursorLoader.loadForWarm(event)).thenReturn(Optional.empty());
    warmService.warm(event);
    verifyNoInteractions(historyLoader, cache);
}

@Test
void rejectedWarmTaskDoesNotEscapeAfterCommitListener() {
    doThrow(new TaskRejectedException("full"))
            .when(executor).execute(any(Runnable.class));
    assertThatCode(() -> listener.afterCommit(event)).doesNotThrowAnyException();
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn -Dtest=ChatHistoryCacheWarmServiceTest,ChatHistoryCacheWarmListenerTest test
```

Expected: compilation failure because warming types do not exist.

- [ ] **Step 3: 配置专用有界线程池**

Create bean `chatHistoryCacheExecutor` using the validated cache properties:

```java
executor.setCorePoolSize(properties.warmCorePoolSize());
executor.setMaxPoolSize(properties.warmMaxPoolSize());
executor.setQueueCapacity(properties.warmQueueCapacity());
executor.setThreadNamePrefix("chat-history-warm-");
executor.setWaitForTasksToCompleteOnShutdown(false);
executor.setAwaitTerminationSeconds(5);
```

Do not reuse `chatStreamExecutor`; cache warm-up must not consume user-facing stream capacity.

- [ ] **Step 4: 实现预热服务与提交后监听器**

Warm service:

```java
public void warm(ChatHistoryChangedEvent event) {
    try {
        cursorLoader.loadForWarm(event).ifPresent(cursor -> {
            ChatHistorySnapshot snapshot = historyLoader.load(cursor);
            cache.put(cursor, snapshot);
        });
    } catch (RuntimeException exception) {
        metrics.warmError();
        log.warn("chat_history_cache_warm_failed conversationId={}, exceptionType={}",
                event.conversationId(), exception.getClass().getSimpleName());
    }
}
```

Listener:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void afterCommit(ChatHistoryChangedEvent event) {
    try {
        executor.execute(() -> warmService.warm(event));
    } catch (TaskRejectedException exception) {
        metrics.warmRejected();
        log.warn("chat_history_cache_warm_rejected conversationId={}",
                event.conversationId());
    }
}
```

Add Micrometer counters `chat.history.cache.warm.success`, `.warm.skipped`, `.warm.error`, and `.warm.rejected`.

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
mvn -Dtest=ChatHistoryCacheWarmServiceTest,ChatHistoryCacheWarmListenerTest test
mvn -DskipTests compile
```

Expected: matching events warm once, stale events skip, failures and queue rejection remain isolated, and main compilation succeeds.

- [ ] **Step 6: 暂存生产代码并提交**

```powershell
git add src/main/java/com/xjjk/agent/chat/config/ChatHistoryCacheConfiguration.java `
        src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmService.java `
        src/main/java/com/xjjk/agent/chat/service/memory/ChatHistoryCacheWarmListener.java `
        src/main/java/com/xjjk/agent/chat/observation/ChatHistoryCacheMetrics.java
git commit -m "feat: warm history cache after commit"
```

### Task 7: 增加运行配置并完成装配验证

**Files:**

- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Test: `src/test/java/com/xjjk/agent/OrderLogisticsAgentServerApplicationTests.java`

- [ ] **Step 1: 写属性绑定与 Spring 装配失败测试**

Add assertions that the application context contains:

```java
assertThat(context.getBean(ChatHistorySnapshotProvider.class)).isNotNull();
assertThat(context.getBean(RedisChatHistorySnapshotCache.class)).isNotNull();
assertThat(context.getBean("chatHistoryCacheExecutor",
        ThreadPoolTaskExecutor.class)).isNotNull();
```

Add `ApplicationContextRunner` cases proving zero TTL, jitter greater than TTL, and max pool smaller than core pool fail startup validation.

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -Dtest=OrderLogisticsAgentServerApplicationTests test`

Expected: context lacks the new beans or required properties.

- [ ] **Step 3: 增加非敏感配置入口**

Append to `src/main/resources/application.properties`:

```properties
agent.chat.history.cache.enabled=${AGENT_CHAT_HISTORY_CACHE_ENABLED:true}
agent.chat.history.cache.key-prefix=agent:chat:history:v1
agent.chat.history.cache.ttl=30m
agent.chat.history.cache.ttl-jitter=5m
agent.chat.history.cache.warm-core-pool-size=1
agent.chat.history.cache.warm-max-pool-size=2
agent.chat.history.cache.warm-queue-capacity=100
```

Add equivalent deterministic test values to `src/test/resources/application.properties`, with cache disabled when a test does not provide Redis infrastructure. Do not add Redis passwords or production endpoints to repository files.

- [ ] **Step 4: 运行装配和完整测试**

Run:

```powershell
mvn test
mvn -DskipTests package
```

Expected: all tests PASS and the application package builds successfully.

- [ ] **Step 5: 暂存生产配置并提交**

Per repository policy, do not stage test resource changes:

```powershell
git add src/main/resources/application.properties
git commit -m "config: configure chat history cache"
```

### Task 8: 本地 Redis/MySQL 集成验收

**Files:**

- No production file changes expected.

- [ ] **Step 1: 确认基础设施健康**

Run:

```powershell
docker compose --env-file .env.local ps
docker compose --env-file .env.local exec redis redis-cli ping
```

Expected: MySQL and Redis are healthy; Redis returns `PONG`.

- [ ] **Step 2: 启动 Agent 并进行首次回源**

Use a valid SSPX Bearer Token to call `POST /api/v1/chat/stream` through the existing Gateway. Expected logs for a new stable version:

```text
chat_history_cache_miss
chat_history_snapshot
chat_history_cache_write_success
```

The SSE stream must still emit `session`, `status`, `delta`, and `done` in order.

- [ ] **Step 3: 验证收尾后预热和下一轮命中**

After the first request reaches `done`, wait for the bounded warm executor and send the next message in the same conversation. Expected:

```text
chat_history_cache_write_success source=warm
chat_history_cache_hit
```

Verify the answer uses previous conversation context and the MySQL message list remains authoritative.

- [ ] **Step 4: 验证 Redis 停机降级**

Stop only the Redis service, send another message, and verify:

```text
chat_history_cache_error
chat_history_snapshot
```

Expected: conversation still succeeds through MySQL; the client does not receive a Redis-specific error.

- [ ] **Step 5: 验证 Redis 恢复与补写**

Restart Redis, send two more messages in the same conversation. Verify that
the first request either falls back and repopulates the cache or finds a
completed warm entry, and that the following version can hit. No authentication
data or message content may appear in cache logs.

- [ ] **Step 6: 最终差异和安全检查**

Run:

```powershell
git diff --check
rg -n "password|client-secret|api-key|Bearer " src/main/java src/main/resources
git status --short
```

Expected: no whitespace errors; no newly hard-coded secrets; only intended production files staged and local test files remain unstaged according to repository policy.

## Completion Criteria

- Every request validates identity, active ownership and stable cursor against MySQL before consulting Redis.
- A valid cache hit skips MySQL history metadata/body loading but never skips MySQL cursor validation.
- Redis read/write/serialization/timeout failures always fall back to MySQL.
- A committed finish or recovery can warm the new version without extending the database transaction or delaying SSE completion.
- A rollback never warms Redis.
- Stale or malformed cache values are never used.
- Cache keys isolate tenant, user, conversation, stable version, boundary and policy.
- Unit, context, package and local infrastructure checks all pass.
