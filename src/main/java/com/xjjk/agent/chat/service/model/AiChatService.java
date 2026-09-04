package com.xjjk.agent.chat.service.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

@Service
public class AiChatService {

    private final ChatClient chatClient;

    public AiChatService(
            @Qualifier("agentChatClient") ChatClient chatClient
    ) {
        this.chatClient = chatClient;
    }

    public Flux<ChatResponse> stream(String message) {
        Assert.hasText(message, "消息内容不能为空");

        // Flux.defer: 把里面的调用延迟到被订阅时执行
        return Flux.defer(() -> chatClient
                .prompt()
                .user(message)
                .stream()
                .chatResponse());
    }
}
