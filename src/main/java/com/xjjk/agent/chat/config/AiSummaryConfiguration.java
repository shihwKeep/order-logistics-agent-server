package com.xjjk.agent.chat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 为滚动摘要提供与客服对话隔离的模型选项和有界阻塞调用线程。 */
@Configuration(proxyBeanMethods = false)
public class AiSummaryConfiguration {

    @Bean
    public ChatClient summaryChatClient(
            ChatClient.Builder builder,
            ChatSummaryProperties properties
    ) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(Math.toIntExact(properties.maxOutputTokens()))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .extraBody(Map.of("enable_thinking", false))
                .build();
        return builder.defaultOptions(options).build();
    }

    /**
     * 不能复用 chatSummaryExecutor：Worker 在其中等待模型结果，复用会造成线程池自锁。
     */
    @Bean(name = "chatSummaryModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService chatSummaryModelExecutor(
            ChatSummaryProperties properties
    ) {
        int poolSize = properties.worker().maxPoolSize();
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.worker().queueCapacity()),
                new CustomizableThreadFactory("summary-model-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
