package com.xjjk.agent.memory.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.xjjk.agent.memory.service.ImplicitMemoryCandidateValidator;
import com.xjjk.agent.memory.service.MemorySchemaRegistry;
import com.xjjk.agent.memory.service.MemorySensitiveContentPolicy;
import com.xjjk.agent.memory.service.MemoryTemporalEvidencePolicy;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class ImplicitMemoryConfiguration {

    @Bean
    public ChatClient implicitMemoryChatClient(
            ChatClient.Builder builder,
            ImplicitMemoryProperties properties
    ) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(512)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .extraBody(Map.of("enable_thinking", false))
                .build();
        return builder.defaultOptions(options).build();
    }

    @Bean(name = "implicitMemoryModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService implicitMemoryModelExecutor(ImplicitMemoryProperties properties) {
        ImplicitMemoryProperties.Executor settings = properties.modelExecutor();
        return new ThreadPoolExecutor(
                settings.poolSize(), settings.poolSize(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()),
                new CustomizableThreadFactory("memory-extract-model-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "implicitMemoryWorkerExecutor")
    public ThreadPoolTaskExecutor implicitMemoryWorkerExecutor(ImplicitMemoryProperties properties) {
        ImplicitMemoryProperties.Executor settings = properties.worker().executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.poolSize());
        executor.setMaxPoolSize(settings.poolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("memory-extract-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean
    public ImplicitMemoryCandidateValidator implicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitivePolicy,
            MemorySchemaRegistry schemaRegistry,
            MemoryTemporalEvidencePolicy temporalEvidencePolicy,
            ImplicitMemoryProperties implicitProperties,
            UserMemoryProperties memoryProperties
    ) {
        return new ImplicitMemoryCandidateValidator(
                sensitivePolicy, schemaRegistry, temporalEvidencePolicy,
                implicitProperties,
                memoryProperties.maxContentLength(), memoryProperties.maxEvidenceLength());
    }
}
