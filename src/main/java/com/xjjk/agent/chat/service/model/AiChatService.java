package com.xjjk.agent.chat.service.model;

import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.service.memory.RequestChatMemory;
import com.xjjk.agent.product.tool.ProductQueryTools;
import com.xjjk.agent.product.tool.ProductToolRequestContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Map;

@Service
public class AiChatService {

    private final ChatClient chatClient;
    private final ProductQueryTools productQueryTools;

    public AiChatService(
            @Qualifier("agentChatClient") ChatClient chatClient,
            ProductQueryTools productQueryTools
    ) {
        this.chatClient = chatClient;
        this.productQueryTools = productQueryTools;
    }

    /**
     * 使用已经完成预算筛选的历史进行流式对话。
     *
     * 每次订阅创建独立的记忆和 Advisor，
     * 不跨请求共享临时消息，不由框架直接写入业务消息表。
     *
     * @param message 当前用户问题，应与筛选时使用的问题一致
     * @param selection 已完成权限检查和预算筛选的上下文
     * @param toolRequestContext 仅在服务端流转的工具调用身份和 SSE 输出上下文
     * @return 模型流式响应
     */
    public Flux<ChatResponse> stream(
            String message,
            ChatContextSelection selection,
            ProductToolRequestContext toolRequestContext
    ) {
        Assert.hasText(message, "消息内容不能为空");
        Objects.requireNonNull(selection, "上下文筛选结果不能为空");
        Objects.requireNonNull(toolRequestContext, "工具请求上下文不能为空");

        return Flux.defer(() -> {
            // 在订阅时创建，避免多次订阅复用已经追加过消息的记忆。
            RequestChatMemory memory = new RequestChatMemory(selection);

            // 使用框架原生 Advisor，将历史加入模型请求，
            // 并将本轮消息追加到请求独立的临时记忆。
            MessageChatMemoryAdvisor memoryAdvisor =
                    MessageChatMemoryAdvisor.builder(memory).build();

            return chatClient
                    .prompt()
                    .user(message)
                    // 工具只注册在本次请求，ToolContext 中的可信身份、requestId
                    // 和 SSE 发布器不会进入模型提示词，也不能由模型参数覆盖。
                    .tools(productQueryTools)
                    .toolContext(Map.of(
                            ProductToolRequestContext.CONTEXT_KEY,
                            toolRequestContext))
                    .advisors(spec -> spec
                            .advisors(memoryAdvisor)
                            .param(
                                    ChatMemory.CONVERSATION_ID,
                                    selection.source().conversationId()
                            ))
                    .stream()
                    .chatResponse();
        });
    }
}
