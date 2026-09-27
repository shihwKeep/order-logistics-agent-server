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
    }

    /** 兼容原有调用方；新 Controller 使用 open 读取恢复协议元数据。 */
    public SseEmitter start(
            ChatStreamRequest request,
            AgentIdentity identity
    ) throws IOException {
        return open(request, identity).emitter();
    }

    public ChatStreamOpenSession open(
            ChatStreamRequest request,
            AgentIdentity identity
    ) throws IOException {
        Objects.requireNonNull(request, "聊天请求不能为空");
        Objects.requireNonNull(identity, "认证身份不能为空");
        String requestId = request.clientRequestId() == null
                ? UUID.randomUUID().toString() : request.clientRequestId();
        ChatStreamRequest effectiveRequest = request.clientRequestId() == null
                ? new ChatStreamRequest(
                        request.conversationId(), request.message(), request.action(), requestId)
                : request;
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(properties.timeout());

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
            if (created == ChatReplayCreateResult.CREATED) {
                startReplayProducer(effectiveRequest, identity, requestId, expiresAt);
            }
            metrics.mode("resumable");
            return new ChatStreamOpenSession(
                    relayService.resume(identity, requestId, 0L),
                    requestId,
                    replayRepository.status(identity, requestId)
                            .map(snapshot -> snapshot.expiresAt())
                            .orElse(expiresAt),
                    true);
        }
        metrics.mode("direct");
        return startDirect(effectiveRequest, identity, requestId, expiresAt);
    }

    public ChatStreamCancelResponse cancel(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        boolean local = jobRegistry.cancel(identity, requestId);
        try {
            boolean distributed = replayRepository.requestCancel(identity, requestId);
            metrics.cancel(distributed || local ? "accepted" : "terminal");
            return new ChatStreamCancelResponse(requestId, distributed || local);
        } catch (ChatReplayUnavailableException unavailable) {
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
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                replayRepository, identity, requestId, expiresAt);
        publisher.metrics(metrics);
        ChatStreamControl control = new ChatStreamControl(
                new ChatReplayCancellationProbe(
                        replayRepository, identity, requestId, Duration.ofMillis(250)));
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
        if (!jobRegistry.register(job)) {
            publishStartupError(publisher, requestId,
                    "CHAT_DUPLICATE_REQUEST", "该请求正在处理中");
            return;
        }

        try {
            job.bindHeartbeat(heartbeat.start(publisher, control));
            job.bindDeadline(deadline.schedule(expiresAt, Instant.now(), control));
            executor.execute(task);
        } catch (TaskRejectedException exception) {
            control.requestStop(MessageStatus.CANCELLED);
            publishStartupError(publisher, requestId,
                    "CHAT_BUSY", "当前请求较多，请稍后重试");
            jobRegistry.remove(requestId, job);
        } catch (RuntimeException exception) {
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
            publisher.error(new ChatStreamError(code, message), requestId);
        } catch (IOException exception) {
            throw new IllegalStateException("写入聊天启动错误失败", exception);
        }
    }

    private ChatStreamOpenSession startDirect(
            ChatStreamRequest request,
            AgentIdentity identity,
            String requestId,
            Instant expiresAt
    ) throws IOException {
        // start 只负责建立 SSE 通道和调度后台任务，不在 Tomcat 请求线程中执行模型调用。
        // 30 秒是整条 SSE 请求的生命周期上限，不会因为持续发送 delta 而重新计时。
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());

        // ChatSseSession 统一维护事件序号和 session/status/delta/done/error 协议；
        // ChatStreamControl 统一保存客户端断开、超时等停止信号，供工作线程主动结束模型流。
        ChatSseSession session = new ChatSseSession(emitter);
        session.metrics(metrics);
        ChatStreamControl control = new ChatStreamControl();
        ChatSseHeartbeat.Lease heartbeatLease = heartbeat.start(session, control);

        // 正式 requestId 要到开始事务成功后才能取得。若准备阶段就失败，
        // 仍使用该临时 ID 返回错误并关联日志，避免错误响应没有排查标识。
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

        // 必须先绑定任务并注册生命周期回调，再提交线程池，防止任务刚启动时
        // 客户端已经断开，但停止信号还无法传递给正在运行的模型流。
        control.bind(task);
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

        try {
            // 从这里开始脱离请求线程异步执行；start 随后立即把 emitter 返回给 Controller。
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
        return new ChatStreamOpenSession(emitter, requestId, expiresAt, false);
    }
}
