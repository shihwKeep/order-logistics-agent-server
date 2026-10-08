package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamCancelResponse;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.observation.ChatStreamReplayMetrics;
import com.xjjk.agent.chat.observation.AgentTurnTelemetry;
import com.xjjk.agent.chat.replay.ChatReplayCreateResult;
import com.xjjk.agent.chat.replay.ChatReplayMetadata;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ChatReplayUnavailableException;
import com.xjjk.agent.chat.replay.ReplayChatEventPublisher;
import com.xjjk.agent.chat.stream.ChatEventPublisher;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.chat.stream.ChatStreamError;
import com.xjjk.agent.chat.stream.ChatStreamEventDelay;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式请求的接入与调度服务。
 * 在任务提交前绑定取消回调；被拒绝的任务不创建业务消息、不占用会话。
 * 不持有数据库事务，不保存跨请求的可变执行状态。
 */
@Service
public class ChatStreamService {

    private final ThreadPoolTaskExecutor executor;
    private final ChatTurnRunner runner;
    private final ChatStreamProperties properties;
    private final ChatSseHeartbeat heartbeat;
    private final ChatReplayRepository replayRepository;
    private final ChatSseRelayService relayService;
    private final ChatTurnJobRegistry jobRegistry;
    private final ChatTurnDeadline deadline;
    private final ChatStreamReplayMetrics metrics;
    private final AgentTurnTelemetry turnTelemetry;
    private final ChatStreamEventDelay eventDelay;

    public ChatStreamService(
            @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor executor,
            ChatTurnRunner runner,
            ChatStreamProperties properties,
            ChatSseHeartbeat heartbeat,
            ChatReplayRepository replayRepository,
            ChatSseRelayService relayService,
            ChatTurnJobRegistry jobRegistry,
            ChatTurnDeadline deadline,
            ChatStreamReplayMetrics metrics,
            AgentTurnTelemetry turnTelemetry
    ) {
        // 构造阶段只接收无状态协作者并做非空校验；单轮请求的 requestId、控制器和任务对象
        // 都在 open/startDirect/startReplayProducer 内部创建，避免 Spring 单例串请求状态。
        this.executor = Objects.requireNonNull(executor, "聊天生产线程池不能为空");
        this.runner = Objects.requireNonNull(runner, "聊天执行器不能为空");
        this.properties = Objects.requireNonNull(properties, "聊天流配置不能为空");
        this.heartbeat = Objects.requireNonNull(heartbeat, "聊天心跳不能为空");
        this.replayRepository = Objects.requireNonNull(replayRepository, "回放仓储不能为空");
        this.relayService = Objects.requireNonNull(relayService, "SSE 中继服务不能为空");
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "任务注册表不能为空");
        this.deadline = Objects.requireNonNull(deadline, "任务截止控制不能为空");
        this.metrics = Objects.requireNonNull(metrics, "聊天流指标不能为空");
        this.turnTelemetry = Objects.requireNonNull(turnTelemetry, "Agent 单轮遥测不能为空");
        this.eventDelay = new ChatStreamEventDelay(properties.testDelayMs());
    }

    /** 兼容原有调用方；新 Controller 使用 open 读取恢复协议元数据。 */
    public SseEmitter start(
            ChatStreamRequest request,
            AgentIdentity identity
    ) throws IOException {
        // 旧调用方只关心 SSE 通道，不需要恢复协议中的 requestId、过期时间和可恢复标记；
        // 统一委托给 open，保证旧入口和新入口使用完全相同的建流及调度逻辑。
        return open(request, identity).emitter();
    }

    /**
     * 打开一轮聊天流，并根据 Redis 回放能力选择“可恢复模式”或“直连模式”。
     *
     * <p>该方法只完成协议初始化和后台任务调度，不在 HTTP 请求线程中执行模型调用。</p>
     */
    public ChatStreamOpenSession open(
            ChatStreamRequest request,
            AgentIdentity identity
    ) throws IOException {
        // 第一步：校验可信入参。identity 来自服务端认证上下文，不能由请求正文覆盖。
        Objects.requireNonNull(request, "聊天请求不能为空");
        Objects.requireNonNull(identity, "认证身份不能为空");

        // 第二步：优先使用前端生成的 clientRequestId，便于断线后用同一个 ID 恢复；
        // 兼容旧客户端时由服务端补充 UUID，并构造带正式 ID 的不可变请求对象。
        String requestId = request.clientRequestId() == null
                ? UUID.randomUUID().toString() : request.clientRequestId();
        ChatStreamRequest effectiveRequest = request.clientRequestId() == null
                ? new ChatStreamRequest(
                        request.conversationId(), request.message(), request.action(), requestId)
                : request;

        // 第三步：一次问答只有一个绝对截止时间。后续心跳、重连和事件输出都不会续期，
        // 避免网络反复重连把已经超时的后端任务无限延长。
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(properties.timeout());

        // 第四步：Redis 可用时先建立回放元数据。生产者只负责生成一次事件，
        // 当前连接和后续重连都由 relayService 从 Redis Stream 中继给前端。
        if (replayRepository.available()) {
            ChatReplayCreateResult created;
            try {
                created = replayRepository.create(
                        new ChatReplayMetadata(
                                identity.tenantId(), identity.userId(), identity.orgId(),
                                null, requestId, createdAt, expiresAt));
            } catch (ChatReplayUnavailableException unavailable) {
                // Redis 在业务消息创建前不可用时降级为直连；降级后不承诺断点恢复。
                metrics.failure("redis");
                metrics.mode("direct");
                return startDirect(effectiveRequest, identity, requestId, expiresAt);
            }

            // 只有首次成功创建元数据的请求才启动生产任务；重连请求命中 EXISTING 时
            // 只重新建立消费者，避免同一个 requestId 重复调用模型和写入业务消息。
            if (created == ChatReplayCreateResult.CREATED) {
                startReplayProducer(effectiveRequest, identity, requestId, expiresAt);
            }
            metrics.mode("resumable");

            // 第五步：从 sequence=0 开始建立首次中继。中继会先回放已持久化事件，
            // 再持续等待新事件；Controller 同时获得恢复所需的 requestId 和过期时间。
            return new ChatStreamOpenSession(
                    relayService.resume(identity, requestId, 0L),
                    requestId,
                    replayRepository.status(identity, requestId)
                            .map(snapshot -> snapshot.expiresAt())
                            .orElse(expiresAt),
                    true);
        }

        // Redis 回放能力未启用时使用直连 SSE：事件直接写入当前连接，断线后不可续拉。
        metrics.mode("direct");
        return startDirect(effectiveRequest, identity, requestId, expiresAt);
    }

    /** 请求取消一轮聊天；本机任务立即停止，跨实例任务通过 Redis 取消标志感知。 */
    public ChatStreamCancelResponse cancel(AgentIdentity identity, String requestId) {
        // 第一步：尝试取消当前 JVM 中注册的任务。命中时会设置停止原因并取消 Future。
        Objects.requireNonNull(identity, "认证身份不能为空");
        boolean local = jobRegistry.cancel(identity, requestId);
        try {
            // 第二步：无论本机是否命中，都写入分布式取消标志。任务若运行在其他实例，
            // 会由 ChatReplayCancellationProbe 的短周期检查读取该标志并停止模型流。
            boolean distributed = replayRepository.requestCancel(identity, requestId);

            // 第三步：本地或分布式任一侧接受即返回 accepted=true；均未命中通常表示
            // 请求已经进入终态，重复取消按幂等终态处理，不伪造新的执行状态。
            metrics.cancel(distributed || local ? "accepted" : "terminal");
            return new ChatStreamCancelResponse(requestId, distributed || local);
        } catch (ChatReplayUnavailableException unavailable) {
            // Redis 故障时，本机任务已经成功取消仍可向用户确认；只有本机也未命中时
            // 才把分布式取消失败向上抛出，避免错误声称其他实例上的任务已停止。
            if (local) {
                metrics.cancel("accepted");
                return new ChatStreamCancelResponse(requestId, true);
            }
            metrics.cancel("failure");
            throw unavailable;
        }
    }

    private void startReplayProducer(
            ChatStreamRequest request,
            AgentIdentity identity,
            String requestId,
            Instant expiresAt
    ) {
        // 第一步：创建只写 Redis Stream 的事件发布器。生产任务不绑定某一个前端连接，
        // 因此前端断开或更换实例重连都不会改变事件生产位置。
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                replayRepository, identity, requestId, expiresAt, eventDelay);
        publisher.metrics(metrics);

        // 第二步：为本轮任务创建停止控制器，并注入 250ms 节流的 Redis 取消探针；
        // runner 在模型消费、工具调用等安全点检查 control 时可感知跨实例取消。
        ChatStreamControl control = new ChatStreamControl(
                new ChatReplayCancellationProbe(
                        replayRepository, identity, requestId, Duration.ofMillis(250)));

        // 第三步：FutureTask 是实际提交到线程池且可被取消的执行单元。
        // done 回调负责从本机注册表移除任务，防止完成后的任务继续被误判为运行中。
        AtomicReference<ChatTurnJob> jobReference = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            turnTelemetry.run("resumable", requestId, request.conversationId(),
                    () -> runner.run(request, identity, control, publisher, requestId));
            return null;
        }) {
            @Override
            protected void done() {
                ChatTurnJob job = jobReference.get();
                if (job != null) {
                    jobRegistry.remove(requestId, job);
                }
            }
        };
        ChatTurnJob job = new ChatTurnJob(
                requestId, identity, control, task, expiresAt);
        jobReference.set(job);

        // 第四步：提交线程池前先注册本机任务。同一 requestId 已存在时拒绝重复生产，
        // 但仍向 Redis Stream 写入可回放的启动错误，便于当前连接获得明确结果。
        if (!jobRegistry.register(job)) {
            publishStartupError(publisher, requestId,
                    "CHAT_DUPLICATE_REQUEST", "该请求正在处理中");
            return;
        }

        try {
            // 第五步：先绑定心跳和绝对截止任务，再提交业务任务，保证任务一启动就同时
            // 具备连接健康事件、超时停止和本机取消能力。
            job.bindHeartbeat(heartbeat.start(publisher, control));
            job.bindDeadline(deadline.schedule(expiresAt, Instant.now(), control));
            executor.execute(task);
        } catch (TaskRejectedException exception) {
            // 有界线程池拒绝说明 runner 尚未开始，不会创建业务消息；发布繁忙错误并清理注册。
            control.requestStop(MessageStatus.CANCELLED);
            publishStartupError(publisher, requestId,
                    "CHAT_BUSY", "当前请求较多，请稍后重试");
            jobRegistry.remove(requestId, job);
        } catch (RuntimeException exception) {
            // 心跳、截止任务或线程池调度发生其他启动异常时，统一标记输出错误，
            // 将安全错误事件写入回放流并移除本机任务。
            control.requestStop(MessageStatus.OUTPUT_ERROR);
            publishStartupError(publisher, requestId,
                    "CHAT_STREAM_UNAVAILABLE", "聊天流暂不可用，请稍后重试");
            jobRegistry.remove(requestId, job);
        }
    }

    private void publishStartupError(
            ChatEventPublisher publisher,
            String requestId,
            String code,
            String message
    ) {
        try {
            // 启动阶段尚未进入 ChatTurnRunner 的统一收尾，只能由当前服务主动发布终态错误。
            publisher.error(new ChatStreamError(code, message), requestId);
        } catch (IOException exception) {
            // 发布器写入失败意味着错误事件也无法可靠送达，转换为运行时异常交给上层记录。
            throw new IllegalStateException("写入聊天启动错误失败", exception);
        }
    }

    private ChatStreamOpenSession startDirect(
            ChatStreamRequest request,
            AgentIdentity identity,
            String requestId,
            Instant expiresAt
    ) throws IOException {
        // 第一步：startDirect 只建立 SSE 通道和调度后台任务，不在 Tomcat 请求线程中执行模型调用。
        // 30 秒是整条 SSE 请求的生命周期上限，不会因为持续发送 delta 而重新计时。
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());

        // 第二步：ChatSseSession 统一维护事件序号和 session/status/delta/done/error 协议；
        // ChatStreamControl 统一保存客户端断开、超时等停止信号，供工作线程主动结束模型流。
        ChatSseSession session = new ChatSseSession(emitter, eventDelay);
        session.metrics(metrics);
        ChatStreamControl control = new ChatStreamControl();

        // 第三步：直连模式的心跳直接写入当前 SseEmitter，只证明连接仍在存活，
        // 不保存业务事件，也不提供断点续传能力。
        ChatSseHeartbeat.Lease heartbeatLease = heartbeat.start(session, control);

        // 第四步：使用传入的 requestId 关联错误、日志和取消请求。若准备阶段失败，
        // 仍可使用该 ID 返回错误，避免响应缺少排查标识。
        // 使用 FutureTask 是为了把“提交到线程池的任务”和“可取消对象”合并为同一个实例。
        // runner 内部才会创建业务消息、读取上下文、调用模型并完成数据库收尾。
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                turnTelemetry.run("direct", requestId, request.conversationId(),
                        () -> runner.run(request, identity, control, session, requestId));
            } finally {
                heartbeatLease.close();
            }
            return null;
        });

        // 第五步：必须先绑定任务并注册生命周期回调，再提交线程池，防止任务刚启动时
        // 客户端已经断开，但停止信号还无法传递给正在运行的模型流。
        control.bind(task);
        emitter.onCompletion(() -> {
            // 正常完成或客户端主动关闭时，停止心跳并请求取消仍在运行的后台任务。
            heartbeatLease.close();
            control.requestStop(MessageStatus.CANCELLED);
        });
        emitter.onError(exception -> {
            // 网络写出异常属于输出链路失败，使用 OUTPUT_ERROR 与用户主动取消区分。
            heartbeatLease.close();
            control.requestStop(MessageStatus.OUTPUT_ERROR);
        });
        emitter.onTimeout(() -> {
            // Servlet SSE 通道超时后先设置统一停止原因，再关闭通道，runner 最终仍会进入收尾器。
            heartbeatLease.close();
            control.requestStop(MessageStatus.TIMEOUT);
            emitter.complete();
        });

        try {
            // 第六步：从这里开始脱离请求线程异步执行；方法随后立即把 emitter 返回给 Controller。
            executor.execute(task);
        } catch (TaskRejectedException exception) {
            // 有界线程池已满时 runner 从未执行，因此不会创建消息或占用会话；
            // 直接通过当前 SSE 通道返回繁忙错误即可，不需要执行数据库收尾。
            control.requestStop(MessageStatus.CANCELLED);
            heartbeatLease.close();
            try {
                session.error(new ChatStreamError("CHAT_BUSY", "当前请求较多，请稍后重试"),
                        requestId);
            } finally {
                session.complete();
            }
        }

        // 第七步：返回直连会话元数据。resumable=false 告知 Controller/前端不能按序号恢复。
        return new ChatStreamOpenSession(emitter, requestId, expiresAt, false);
    }
}
