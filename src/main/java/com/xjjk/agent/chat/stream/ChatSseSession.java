package com.xjjk.agent.chat.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 兼容旧调用名称的直连事件发布器。
 * 新业务代码应依赖 {@link ChatEventPublisher}，避免把任务生命周期绑定到连接对象。
 */
public final class ChatSseSession extends DirectChatEventPublisher {

    public ChatSseSession(SseEmitter emitter) {
        super(emitter);
    }

    public ChatSseSession(SseEmitter emitter, ChatStreamEventDelay eventDelay) {
        super(emitter, eventDelay);
    }
}
