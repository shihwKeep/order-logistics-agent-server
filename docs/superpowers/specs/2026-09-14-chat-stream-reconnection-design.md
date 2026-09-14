# Chat Stream Reconnection and Replay Design

## 1. Goal

Add production-grade recovery for transient chat SSE disconnections without submitting the user's question or executing the Agent turn twice.

The feature must:

- keep the original backend turn running after an accidental network disconnect;
- reconnect with bounded exponential backoff and jitter;
- replay only events the client has not received;
- keep the composer and competing chat actions locked while automatic recovery is active;
- stop retrying and notify the user after the configured attempt limit;
- distinguish an explicit user cancellation from an accidental transport disconnect;
- preserve tenant and user isolation across every start, resume, status, and cancel operation;
- degrade to the current non-resumable direct SSE path when Redis is unavailable before a turn starts.

## 2. Scope and Non-goals

This design covers transient network, gateway, idle-timeout, and premature-EOF failures while the Electron application remains running.

It does not persist a live stream cursor on the workstation. If Electron exits or crashes, the next launch restores durable messages from MySQL through the existing conversation-history flow rather than resuming the old SSE connection.

It does not restart or migrate a running model invocation after the Agent process that owns it crashes. Events already committed to Redis remain replayable. Once the original turn deadline passes, the existing conditional recovery/finalization path marks the abandoned turn terminal without allowing a stale owner to overwrite newer state.

## 3. Time Model

Three independent clocks are used:

1. The backend turn deadline remains `agent.chat.stream.timeout=30s`. It starts with the original POST and includes queueing, preparation, context and memory retrieval, knowledge/tool calls, model generation, answer persistence, and terminal confirmation. Reconnection never pauses, refreshes, or extends this deadline.
2. Electron may perform at most five reconnect attempts for the entire turn. Nominal delays are 500 ms, 1 s, 2 s, 4 s, and 8 s, with bounded jitter.
3. Redis replay data expires two minutes after the last appended event. This buffer permits event recovery; it does not extend model execution or replace MySQL message persistence.

Post-turn asynchronous summary generation, implicit-memory extraction, Milvus/Elasticsearch indexing, and cache warming are outside the 30-second user-visible turn deadline.

## 4. Selected Architecture

Redis Streams are the canonical short-lived event journal for resumable turns.

Alternatives were rejected:

- JVM memory cannot support reconnects routed to another instance and is lost on process restart.
- A MySQL row per delta and heartbeat would create unnecessary write amplification and cleanup pressure on the durable business database.

For a resumable turn, generation and delivery are separated:

```text
Agent turn producer
    -> atomic Redis event append
    -> Redis Stream
    -> one or more short-lived SSE relays
    -> Electron
```

The producer does not own an `SseEmitter`. Closing an emitter detaches only that relay. It does not cancel the Agent turn.

For a direct fallback turn, the existing `ChatSseSession` continues to write to the current emitter and retains its existing cancellation-on-disconnect behavior.

## 5. Request Identity and Idempotency

Electron main generates a cryptographically random UUID before the initial HTTP request. Renderer input cannot choose or override it. The backend request DTO receives it as `clientRequestId`, validates UUID syntax, and uses it as the trusted turn `requestId` after binding it to the authenticated identity.

The current MySQL schema already enforces one user message and one assistant message per globally unique request ID with `uk_request_role (request_id, role)`. This constraint is retained; no new message-table uniqueness migration is needed. Every request-ID lookup still includes authenticated tenant and user predicates even though the UUID uniqueness constraint is global.

The effective uniqueness rule is:

```text
(request_id, role)
```

Redis uses an atomic create-if-absent claim for the active request. A duplicate initial POST from the same authenticated user attaches to the existing request. It never inserts another message pair or invokes the model again.

A request ID owned by another tenant or user is reported as inaccessible; the API does not reveal whether that identifier exists.

## 6. HTTP API

The initial endpoint remains:

```http
POST /api/v1/chat/stream
Accept: text/event-stream
Content-Type: application/json
```

The internal main-process request body adds `clientRequestId`. The server returns recovery metadata in response headers so Electron can recover even if the connection drops before the first `session` event:

```text
X-Chat-Request-Id
X-Chat-Expires-At
X-Chat-Resumable
X-Chat-Reconnect-Max-Attempts
X-Chat-Reconnect-Initial-Backoff-Ms
X-Chat-Reconnect-Max-Backoff-Ms
X-Chat-Reconnect-Jitter-Ratio
```

The `session` payload also includes `expiresAt` and `resumable` for protocol observability.

Resume endpoint:

```http
GET /api/v1/chat/stream/{requestId}/resume?afterSequence={lastSequence}
Accept: text/event-stream
Authorization: Bearer ...
```

Status endpoint:

```http
GET /api/v1/chat/stream/{requestId}/status
Authorization: Bearer ...
```

Status returns identity-safe metadata only: request ID, conversation ID when available, state, resumable flag, deadline, last sequence, terminal message ID, and terminal error code. It does not return answer content.

Explicit cancellation endpoint:

```http
POST /api/v1/chat/stream/{requestId}/cancel
Authorization: Bearer ...
```

Resume, status, and cancel construct lookups from the authenticated `tenantId` and `userId`; they never accept identity fields from query parameters or request bodies.

## 7. Redis Model

Keys share a Redis Cluster hash tag so metadata and events can be updated atomically:

```text
agent:chat:stream:v1:{tenantId:userId:requestId}:meta
agent:chat:stream:v1:{tenantId:userId:requestId}:events
agent:chat:stream:v1:{tenantId:userId:requestId}:control
```

Metadata includes:

```text
tenantId, userId, orgId, conversationId, requestId
state, resumable, createdAt, expiresAt
lastSequence, totalEventCount, totalEventBytes
ownerInstanceId, ownerLeaseUntil, activeConnectionId
terminalMessageId, terminalErrorCode
```

Each Stream entry contains:

```text
sequence, type, timestamp, payloadJson, payloadBytes
```

The Redis Stream ID is `<sequence>-0`.

An atomic Lua append operation:

1. verifies that the request is writable and not terminal;
2. validates per-event, event-count, and total-byte limits;
3. increments `lastSequence`;
4. appends the event;
5. updates counters and terminal state when applicable;
6. refreshes metadata, event, and control TTLs.

This provides one ordering boundary for model, tool, heartbeat, and terminal events. Once `done` or `error` commits, later writes are rejected.

## 8. Capacity and Retention

Default Nacos properties:

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

The implementation reserves capacity for one small terminal event. If an event or stream limit would be exceeded, generation stops and the request becomes `CHAT_STREAM_REPLAY_LIMIT`. Existing events are never silently trimmed because trimming could make replay produce a false complete answer.

TTL is refreshed on each append and finalized from the `done/error` append. When replay data expires, durable chat messages remain in MySQL.

## 9. Relays and Connection Supersession

Initial and resume connections are Redis Stream relays. A relay:

1. replays entries strictly greater than `afterSequence`;
2. blocks for new entries for at most the configured read-block timeout;
3. rechecks connection ownership, request terminal state, and turn deadline;
4. completes after forwarding `done/error`.

Attaching a relay assigns a new random `activeConnectionId`. A previous relay observes that it has been superseded and stops. Multiple connections may briefly overlap, but immutable sequence IDs and frontend deduplication prevent duplicate rendering.

SSE relay completion, error, or timeout detaches only the relay for resumable turns. Explicit cancellation is the only client action that requests turn cancellation.

## 10. Cancellation and Process Failure

The cancel endpoint atomically marks a durable Redis cancellation intent and attempts immediate cancellation through a local active-task registry. The owning instance observes cancellation through Redis notification plus checks at preparation, tool, model-chunk, heartbeat, and finalization boundaries. The durable cancellation flag is the fallback if notification is missed.

Cancellation finalizes the assistant message as `CANCELLED` and publishes a terminal error event when Redis is available.

If the producer process crashes, no other instance restarts the model. A resume/status request may replay committed events, but after `expiresAt` it conditionally finalizes the still-owned database turn as `TIMEOUT` or `INTERRUPTED`. Existing request-ID and database lease conditions prevent a stale producer from overwriting recovered state.

## 11. Redis Failure Semantics

Before turn creation, failure to initialize replay storage selects the existing direct SSE mode. The session is marked `resumable=false`; Electron does not attempt resume for that turn. Chat availability is preserved.

Once a turn is declared resumable, Redis is part of the correctness boundary. An append failure cannot be treated as success because clients could miss answer content. The turn finalizes as `CHAT_STREAM_REPLAY_UNAVAILABLE`. If Redis cannot carry the terminal event, a later status request reconciles the durable MySQL state.

No turn changes between direct and resumable mode after it starts.

## 12. Electron Main-process Recovery

`AgentChatStreamClient` owns recovery because it already owns the access token and the actual Fetch readable stream.

Per active stream it retains:

```text
local streamId, owner renderer ID
server requestId, conversationId
last accepted sequence, expiresAt, resumable
current reader/controller
cumulative reconnect attempts
unresolved terminal status when recovery is exhausted
```

On a retryable failure it returns a connection-state result to the renderer, applies exponential backoff with bounded jitter, opens the resume endpoint with `afterSequence`, replaces the reader, and continues parsing.

The five-attempt budget is cumulative for the entire original request. A successful reconnect does not reset the counter, preventing an unstable network from creating an unbounded loop.

Electron never automatically repeats the initial POST.

## 13. Renderer State and Input Locking

The shared IPC read result gains a transport-state variant separate from server `ChatEvent`; local recovery state never consumes or fabricates a server event sequence.

While recovery is active:

- the existing active controller remains installed;
- `chat.isStreaming` and `chat.isBusy` remain true;
- the composer, send button, card operations, conversation switching, and new-conversation action remain disabled;
- status text shows `连接中断，正在恢复（N/5）`;
- already-rendered assistant content remains visible.

After successful resume, status returns to the next server status/event and missing events are applied. `ChatAccumulator` continues deduplicating by sequence.

After the fifth failure:

- automatic recovery stops;
- the partial assistant message is marked incomplete and is not represented as a successful answer;
- the UI shows `连接恢复失败，请稍后重试`;
- the composer unlocks;
- Electron retains an unresolved request record for the running application.

Before a later user message is added to the UI or sent, Electron queries the status endpoint:

- `DONE`: refresh conversation history, clear the unresolved request, and then allow a new send;
- `ERROR`, `TIMEOUT`, `CANCELLED`, or `INTERRUPTED`: clear the unresolved request and allow a new send;
- `RUNNING`: do not send and show `上一轮仍在处理中`;
- status network failure: do not send, retain the user's input text, and show that the prior state cannot yet be confirmed.

## 14. Retry Classification

Retryable:

- Fetch network rejection not caused by explicit abort;
- TCP reset, premature EOF without `done/error`, and established-stream idle timeout;
- HTTP 408, 429, 502, 503, and 504 from resume/status endpoints;
- temporary Redis-unavailable responses from resume.

Not retryable:

- a server `error` terminal event;
- HTTP 400 or 403;
- HTTP 401, which triggers logout;
- HTTP 404 or 410 for a missing or expired replay stream;
- malformed, oversized, or over-queued SSE data;
- explicit user cancellation;
- a received `done` event.

The delay before attempt `n` is `min(maxBackoff, initialBackoff * 2^(n-1))`, multiplied by bounded random jitter in `[0.8, 1.2]`. `Retry-After` may increase the wait for 429/503 but cannot extend the backend turn deadline.

## 15. Terminal Races

Network restoration and turn completion are independent:

- If the turn completed within its deadline, a later resume replays missing events and `done`, even if the wall clock is now past the original deadline.
- If the turn did not complete before the deadline, resume/status returns or synthesizes the durable `CHAT_TIMEOUT` terminal result.
- A successful HTTP reconnect therefore does not imply a successful Agent turn.

Terminal writes use request ID, message status, and active lease/request conditions. Only one terminal transition wins.

## 16. Gateway Requirements

The chat start and resume routes must:

- disable proxy buffering and response compression for SSE;
- use a read/response timeout greater than the backend turn deadline plus terminal-delivery grace (at least 45 seconds for a 30-second turn);
- preserve authorization and the defined recovery response headers;
- not automatically replay the initial POST.

## 17. Observability

Metrics:

```text
chat_stream_reconnect_attempt_total
chat_stream_reconnect_success_total
chat_stream_reconnect_exhausted_total
chat_stream_replay_events_total
chat_stream_replay_latency
chat_stream_replay_redis_error_total
chat_stream_active_jobs
chat_stream_active_relays
```

Structured logs include request ID, connection ID, attempt number, sequence range, state, duration, and failure category. Event payloads, access tokens, user messages, and tool result contents are not logged.

## 18. Verification

Backend tests cover:

- property validation;
- atomic concurrent model/heartbeat append ordering;
- replay strictly after a cursor;
- terminal-write exclusion;
- TTL, per-event, event-count, and byte limits;
- authenticated tenant/user isolation;
- duplicate initial-request idempotency;
- accidental disconnect detach versus explicit cancellation;
- Redis-startup failure direct-mode fallback;
- mid-turn Redis failure safe termination;
- timeout/completion races and stale-owner protection;
- resume and status behavior after event expiry.

Electron tests use injected clocks, random values, and Fetch implementations to cover:

- 500 ms, 1 s, 2 s, 4 s, and 8 s nominal backoff with jitter bounds;
- cumulative five-attempt limit;
- retryable and non-retryable classification;
- cursor advancement and replay deduplication;
- busy/input lock through recovery;
- explicit stop during delay and during a resumed stream;
- exhaustion notification and unresolved status reconciliation;
- user input preservation when reconciliation cannot complete;
- no automatic initial POST replay.

Integration verification disconnects after a known sequence, resumes through a different server instance against the same Redis, checks ordered replay through `done`, and confirms the Agent turn and message pair were created exactly once.
