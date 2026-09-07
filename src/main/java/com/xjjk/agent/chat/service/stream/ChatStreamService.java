package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.MessageStatus;
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
import java.util.UUID;
import java.util.concurrent.FutureTask;

/**
 * 流式请求的接入与调度服务。
 * 在任务提交前绑定取消回调；被拒绝的任务不创建业务消息、不占用会话。
 * 不持有数据库事务，不保存跨请求的可变执行状态。
 */
@Service
public class ChatStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 30_000L;

    private final ThreadPoolTaskExecutor executor;
    private final ChatTurnRunner runner;

    public ChatStreamService(
            @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor executor,
            ChatTurnRunner runner
    ) {
        this.executor = executor;
        this.runner = runner;
    }

    public SseEmitter start(
            ChatStreamRequest request,
            AgentIdentity identity
    ) throws IOException {
        // start 只负责建立 SSE 通道和调度后台任务，不在 Tomcat 请求线程中执行模型调用。
        // 30 秒是整条 SSE 请求的生命周期上限，不会因为持续发送 delta 而重新计时。
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);

        // ChatSseSession 统一维护事件序号和 session/status/delta/done/error 协议；
        // ChatStreamControl 统一保存客户端断开、超时等停止信号，供工作线程主动结束模型流。
        ChatSseSession session = new ChatSseSession(emitter);
        ChatStreamControl control = new ChatStreamControl();

        // 正式 requestId 要到开始事务成功后才能取得。若准备阶段就失败，
        // 仍使用该临时 ID 返回错误并关联日志，避免错误响应没有排查标识。
        String fallbackRequestId = UUID.randomUUID().toString();

        // 使用 FutureTask 是为了把“提交到线程池的任务”和“可取消对象”合并为同一个实例。
        // runner 内部才会创建业务消息、读取上下文、调用模型并完成数据库收尾。
        FutureTask<Void> task = new FutureTask<>(() -> {
            runner.run(request, identity, control, session, fallbackRequestId);
            return null;
        });

        // 必须先绑定任务并注册生命周期回调，再提交线程池，防止任务刚启动时
        // 客户端已经断开，但停止信号还无法传递给正在运行的模型流。
        control.bind(task);
        emitter.onCompletion(() -> control.requestStop(MessageStatus.CANCELLED));
        emitter.onError(exception -> control.requestStop(MessageStatus.OUTPUT_ERROR));
        emitter.onTimeout(() -> {
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
            try {
                session.error(new ChatStreamError("CHAT_BUSY", "当前请求较多，请稍后重试"),
                        fallbackRequestId);
            } finally {
                session.complete();
            }
        }
        return emitter;
    }
}
