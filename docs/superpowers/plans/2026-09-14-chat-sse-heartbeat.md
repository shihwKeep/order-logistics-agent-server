# Chat SSE Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 的 POST SSE 问答流增加服务端心跳与 Electron 主进程空闲超时检测，在不自动重放用户请求的前提下尽早识别网络或网关断流。

**Architecture:** Agent 在成功发送 `session` 后按固定间隔发送带统一事件序号的 `heartbeat` 事件，并在任一终态或通道关闭时停止调度；SSE 的 30 秒总生命周期不因心跳续期。Electron 主进程继续负责 HTTP/SSE 读取，收到任意字节即刷新 25 秒空闲计时，渲染进程只校验并吞掉 `heartbeat`，不修改消息内容或 UI 状态。

**Tech Stack:** Java 21、Spring Boot `SseEmitter`、Spring `TaskScheduler`、JUnit 5；Electron、TypeScript、Fetch `ReadableStream`、Vitest。

---

### Task 1: Agent SSE 心跳协议与状态机

**Files:**
- Create: `src/test/java/com/xjjk/agent/chat/stream/ChatSseSessionTest.java`
- Modify: `src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java`
- Modify: `src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java`

- [ ] **Step 1: Write the failing protocol tests**

```java
@Test
void heartbeatStartsOnlyAfterSessionAndStopsAfterDone() throws Exception {
    RecordingSseEmitter emitter = new RecordingSseEmitter();
    ChatSseSession session = new ChatSseSession(emitter);

    assertThat(session.heartbeat()).isFalse();
    session.session("conversation-1", "request-1");
    assertThat(session.heartbeat()).isTrue();
    session.done("message-1");
    assertThat(session.heartbeat()).isFalse();

    assertThat(emitter.eventNames()).containsExactly("session", "heartbeat", "done");
    assertThat(emitter.eventIds()).containsExactly("1", "2", "3");
}

@Test
void errorAlsoClosesTheApplicationEventStream() throws Exception {
    RecordingSseEmitter emitter = new RecordingSseEmitter();
    ChatSseSession session = new ChatSseSession(emitter);
    session.session("conversation-1", "request-1");

    session.error(new ChatStreamError("FAILED", "失败"), "request-1");

    assertThat(session.heartbeat()).isFalse();
    assertThat(emitter.eventNames()).containsExactly("session", "error");
}
```

The test emitter records `SseEmitter.SseEventBuilder` instances by calling `builder.build()` and extracting the `event:` and `id:` lines. It must not sleep or use a real scheduler.

- [ ] **Step 2: Run the tests and confirm the missing heartbeat API**

Run: `./mvnw.cmd -Dtest=ChatSseSessionTest test`

Expected: compilation fails because `ChatSseSession.heartbeat()` and `ChatStreamPayloads.Heartbeat` do not exist.

- [ ] **Step 3: Add the heartbeat payload and synchronized terminal state**

Add an empty heartbeat payload:

```java
public record Heartbeat() {
}
```

Update `ChatSseSession` so `session`, `heartbeat`, `done`, `error`, and `complete` are synchronized around one state machine:

```java
private boolean sessionSent;
private boolean terminal;

public synchronized void session(String conversationId, String requestId) throws IOException {
    if (terminal || sessionSent) return;
    sendLocked("session", new ChatStreamPayloads.Session(conversationId, requestId));
    sessionSent = true;
}

public synchronized boolean heartbeat() throws IOException {
    if (!sessionSent || terminal) return false;
    sendLocked("heartbeat", new ChatStreamPayloads.Heartbeat());
    return true;
}

public synchronized void done(String messageId) throws IOException {
    if (terminal) return;
    terminal = true;
    sendLocked("done", new ChatStreamPayloads.Done(messageId));
}

public synchronized void error(ChatStreamError error, String requestId) throws IOException {
    if (terminal) return;
    terminal = true;
    sendLocked("error", new ChatStreamPayloads.Error(error.code(), error.message(), requestId));
}

public synchronized void complete() {
    terminal = true;
    emitter.complete();
}
```

All other public event methods synchronize and delegate to a non-synchronized `sendLocked` method so every event shares one monotonic sequence and no heartbeat can be emitted after `done`/`error`.

- [ ] **Step 4: Run the protocol tests**

Run: `./mvnw.cmd -Dtest=ChatSseSessionTest test`

Expected: PASS.

- [ ] **Step 5: Commit the protocol change**

```powershell
git add src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java src/test/java/com/xjjk/agent/chat/stream/ChatSseSessionTest.java
git commit -m "feat: add heartbeat to chat SSE protocol"
```

### Task 2: Agent 心跳调度和可配置生命周期

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/config/ChatStreamProperties.java`
- Create: `src/main/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeat.java`
- Create: `src/test/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeatTest.java`
- Modify: `src/main/java/com/xjjk/agent/chat/config/ChatStreamConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Write failing scheduler lifecycle tests**

```java
@Test
void sendsHeartbeatAtFixedRateAndCloseCancelsTheSchedule() throws Exception {
    TaskScheduler scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    AtomicReference<Runnable> scheduled = new AtomicReference<>();
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(10))))
            .thenAnswer(invocation -> {
                scheduled.set(invocation.getArgument(0));
                return future;
            });
    ChatSseSession session = mock(ChatSseSession.class);
    ChatStreamControl control = new ChatStreamControl();
    ChatSseHeartbeat heartbeat = new ChatSseHeartbeat(scheduler, Duration.ofSeconds(10));

    AutoCloseable lease = heartbeat.start(session, control);
    scheduled.get().run();
    lease.close();

    verify(session).heartbeat();
    verify(future).cancel(false);
}

@Test
void sendFailureStopsTheTurnAndCancelsFutureTicks() throws Exception {
    // Arrange the same captured scheduler runnable, make session.heartbeat() throw IOException,
    // bind a mocked FutureTask to control, then run the captured heartbeat.
    // Assert requestStop(OUTPUT_ERROR) cancels the turn and the ScheduledFuture is cancelled.
}
```

- [ ] **Step 2: Run the scheduler test and confirm it fails**

Run: `./mvnw.cmd -Dtest=ChatSseHeartbeatTest test`

Expected: compilation fails because `ChatSseHeartbeat` does not exist.

- [ ] **Step 3: Implement validated stream properties and heartbeat scheduler**

Create configuration properties with safe defaults in Nacos/local configuration:

```java
@ConfigurationProperties(prefix = "agent.chat.stream")
public record ChatStreamProperties(Duration timeout, Duration heartbeatInterval) {
    public ChatStreamProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("聊天流超时时间必须大于零");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("聊天流心跳间隔必须大于零且小于总超时");
        }
    }
}
```

Add properties:

```properties
agent.chat.stream.timeout=30s
agent.chat.stream.heartbeat-interval=10s
```

Expose a dedicated `ThreadPoolTaskScheduler` bean with remove-on-cancel enabled and a daemon thread prefix `chat-sse-heartbeat-`. `ChatSseHeartbeat.start` calls `scheduleAtFixedRate`; on `IOException` it requests `MessageStatus.OUTPUT_ERROR` and cancels its own schedule. Its returned idempotent lease cancels with `cancel(false)`.

- [ ] **Step 4: Wire the lifecycle into `ChatStreamService`**

Inject `ChatStreamProperties` and `ChatSseHeartbeat`. Construct `SseEmitter` with `properties.timeout().toMillis()`. Start the heartbeat before submitting the `FutureTask`, but rely on `ChatSseSession.heartbeat()` to emit nothing until `session` succeeds. Close the heartbeat lease in all of these paths:

```java
FutureTask<Void> task = new FutureTask<>(() -> {
    try {
        runner.run(request, identity, control, session, fallbackRequestId);
    } finally {
        heartbeatLease.close();
    }
    return null;
});

emitter.onCompletion(() -> {
    heartbeatLease.close();
    control.requestStop(MessageStatus.CANCELLED);
});
emitter.onError(exception -> {
    heartbeatLease.close();
    control.requestStop(MessageStatus.OUTPUT_ERROR);
});
emitter.onTimeout(() -> {
    heartbeatLease.close();
    control.requestStop(MessageStatus.TIMEOUT);
    emitter.complete();
});
```

If executor submission is rejected, close the lease before sending the `CHAT_BUSY` error. Do not reschedule or extend the emitter timeout after heartbeat events.

- [ ] **Step 5: Run backend heartbeat and stream regression tests**

Run: `./mvnw.cmd -Dtest=ChatSseHeartbeatTest,ChatSseSessionTest,ChatTurnRunnerBusinessQueryTest,ChatTurnRunnerExplicitMemoryTest test`

Expected: PASS.

- [ ] **Step 6: Commit backend scheduling**

```powershell
git add src/main/java/com/xjjk/agent/chat/config/ChatStreamProperties.java src/main/java/com/xjjk/agent/chat/config/ChatStreamConfiguration.java src/main/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeat.java src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java src/main/resources/application.properties src/test/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeatTest.java
git commit -m "feat: schedule chat SSE heartbeats"
```

### Task 3: Electron 心跳协议消费

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/contracts/chat.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/transports/ipc-chat-transport.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.test.ts`

- [ ] **Step 1: Write failing renderer tests**

Add an IPC transport case whose first event is:

```ts
{
  type: 'heartbeat',
  sequence: 2,
  timestamp: '2026-09-14T10:00:00Z',
  payload: {}
}
```

Assert it is accepted and yielded before `done`. Add an accumulator test:

```ts
it('deduplicates heartbeat without changing assistant message state', () => {
  const accumulator = new ChatAccumulator('assistant-heartbeat')
  const heartbeat: ChatEvent = {
    type: 'heartbeat', sequence: 2, timestamp, payload: {}
  }

  accumulator.apply(heartbeat)
  accumulator.apply(heartbeat)

  expect(accumulator.message).toMatchObject({ content: '', results: [], complete: false })
  expect(accumulator.message.createdAt).toBeUndefined()
})
```

- [ ] **Step 2: Run the focused renderer tests and confirm failure**

Run: `npm test -- src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/stores/chat-accumulator.test.ts`

Expected: FAIL because `heartbeat` is not a valid `ChatEvent` and is rejected by the IPC validator.

- [ ] **Step 3: Add heartbeat to the shared renderer contract**

Extend the union and whitelist:

```ts
export type ChatEvent =
  | EventEnvelope<'session', { conversationId: string; requestId: string }>
  | EventEnvelope<'heartbeat', Record<string, never>>
  // existing events remain unchanged
```

In `ChatAccumulator.apply`, deduplicate the sequence first, then return immediately for `heartbeat` before setting `createdAt` or changing content/results/completion. Do not render a status label for heartbeats.

- [ ] **Step 4: Run the renderer tests**

Run: `npm test -- src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/stores/chat-accumulator.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit only the heartbeat protocol files**

```powershell
git add src/renderer/src/contracts/chat.ts src/renderer/src/transports/ipc-chat-transport.ts src/renderer/src/transports/ipc-chat-transport.test.ts src/renderer/src/stores/chat-accumulator.ts src/renderer/src/stores/chat-accumulator.test.ts
git commit -m "feat: consume chat heartbeat events"
```

Do not stage the pre-existing `LogisticsTimelineCard.test.ts`, `order-card-view.test.ts`, or `dist-verify/` changes.

### Task 4: Electron SSE 空闲超时

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/main/agent-chat-stream-client.test.ts`

- [ ] **Step 1: Write a failing idle timeout test with fake timers**

```ts
it('aborts an established stream after 25 seconds without bytes', async () => {
  vi.useFakeTimers()
  const body = new ReadableStream<Uint8Array>({ start() {} })
  const client = new AgentChatStreamClient(
    'http://agent',
    { getAccessToken: async () => 'token' } as AuthSession,
    async () => sseResponse(body),
    10_000,
    25_000
  )

  const streamId = await client.start({ message: '查询订单' })
  const next = client.next(streamId)
  await vi.advanceTimersByTimeAsync(25_000)

  await expect(next).rejects.toMatchObject({
    kind: 'network', code: 'CHAT_STREAM_IDLE_TIMEOUT'
  })
  vi.useRealTimers()
})
```

Add a second test that enqueues a heartbeat before the idle deadline and verifies the timer is restarted for the following read instead of timing out at the original deadline.

- [ ] **Step 2: Run the client test and confirm failure**

Run: `npm test -- src/main/agent-chat-stream-client.test.ts`

Expected: FAIL because the constructor has no idle timeout and `next()` can block forever.

- [ ] **Step 3: Implement per-read idle detection**

Add `DEFAULT_IDLE_TIMEOUT_MS = 25_000` and a fifth constructor parameter. Wrap only `reader.read()` with a timer:

```ts
private async readWithIdleTimeout(
  stream: ActiveStream
): Promise<ReadableStreamReadResult<Uint8Array>> {
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      stream.reader.read(),
      new Promise<never>((_resolve, reject) => {
        timeout = setTimeout(() => {
          reject(new ChatStreamRequestError(
            'network',
            'CHAT_STREAM_IDLE_TIMEOUT',
            '聊天连接长时间没有响应，请重试'
          ))
          stream.controller.abort()
          void stream.reader.cancel().catch(() => undefined)
        }, this.idleTimeoutMs)
        timeout.unref?.()
      })
    ])
  } finally {
    if (timeout) clearTimeout(timeout)
  }
}
```

On this typed timeout, delete the stream and rethrow it. Preserve existing explicit-abort behavior (`done: true`) and do not retry or replay the POST request.

- [ ] **Step 4: Run the main-process client tests**

Run: `npm test -- src/main/agent-chat-stream-client.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit the idle timeout**

```powershell
git add src/main/agent-chat-stream-client.ts src/main/agent-chat-stream-client.test.ts
git commit -m "feat: detect idle chat SSE streams"
```

### Task 5: Cross-repository verification and deployment note

**Files:**
- Modify: `docs/superpowers/specs/2026-09-14-chat-sse-heartbeat-design.md`

- [ ] **Step 1: Verify the Agent server**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS. Confirm no test relies on real-time sleeps for heartbeat behavior.

- [ ] **Step 2: Verify the Electron application**

Run: `npm test`

Expected: all tests PASS, apart from any pre-existing unrelated dirty-test failure which must be recorded without changing those files.

Run: `npm run typecheck`

Expected: PASS.

Run: `npm run build`

Expected: Electron main, preload, and renderer builds complete.

- [ ] **Step 3: Document the gateway requirement without changing the shared gateway**

Append the concrete deployment invariant to the design document:

```markdown
部署约束：`/agent/api/v1/chat/stream` 的网关/Nginx read/response timeout 必须大于
`agent.chat.stream.timeout`（当前 30s，建议至少 45s），且不得缓冲 `text/event-stream`。
心跳只能刷新空闲读超时，不能绕过网关配置的绝对请求时限。
```

The tracked Gateway repository currently has no route-specific Agent SSE timeout, so this plan deliberately does not guess or mutate shared production routing configuration.

- [ ] **Step 4: Review git diffs and commit documentation**

Run in both repositories: `git status --short` and `git diff --check`.

Expected: only intended files are staged/committed; the Electron repository still preserves the user's pre-existing unrelated changes.

```powershell
git add docs/superpowers/specs/2026-09-14-chat-sse-heartbeat-design.md
git commit -m "docs: record chat SSE deployment constraints"
```

