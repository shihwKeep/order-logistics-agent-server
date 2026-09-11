package com.xjjk.agent.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import com.xjjk.agent.memory.service.DeterministicExplicitMemoryCandidateParser;
import com.xjjk.agent.memory.service.ExplicitMemoryCandidateValidator;
import com.xjjk.agent.memory.service.ExplicitMemoryCommandDetector;
import com.xjjk.agent.memory.service.MemorySensitiveContentPolicy;
import com.xjjk.agent.memory.service.MemoryCategoryContentPolicy;
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

@Configuration(proxyBeanMethods = false)
public class AiMemoryConfiguration {

    @Bean
    public ChatClient memoryChatClient(ChatClient.Builder builder, UserMemoryProperties properties) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(256)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .extraBody(Map.of("enable_thinking", false))
                .build();
        return builder.defaultOptions(options).build();
    }

    @Bean(name = "memoryExtractionModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService memoryExtractionModelExecutor(UserMemoryProperties properties) {
        return new ThreadPoolExecutor(
                properties.modelPoolSize(),
                properties.modelPoolSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.modelQueueCapacity()),
                new CustomizableThreadFactory("memory-extraction-model-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean
    public ExplicitMemoryCommandDetector explicitMemoryCommandDetector(UserMemoryProperties properties) {
        return new ExplicitMemoryCommandDetector(properties.maxContentLength());
    }

    @Bean
    public MemorySensitiveContentPolicy memorySensitiveContentPolicy(SensitiveContentSanitizer sanitizer) {
        return new MemorySensitiveContentPolicy(sanitizer);
    }

    @Bean
    public MemoryCategoryContentPolicy memoryCategoryContentPolicy() {
        return new MemoryCategoryContentPolicy();
    }

    @Bean
    public DeterministicExplicitMemoryCandidateParser deterministicExplicitMemoryCandidateParser(
            MemoryCategoryContentPolicy contentPolicy
    ) {
        return new DeterministicExplicitMemoryCandidateParser(contentPolicy);
    }

    @Bean
    public ExplicitMemoryCandidateValidator explicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy policy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            UserMemoryProperties properties
    ) {
        return new ExplicitMemoryCandidateValidator(
                policy,
                categoryContentPolicy,
                properties.maxContentLength(),
                properties.maxEvidenceLength()
        );
    }
}
