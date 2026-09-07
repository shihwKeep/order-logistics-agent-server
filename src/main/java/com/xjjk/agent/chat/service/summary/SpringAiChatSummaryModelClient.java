package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryGenerationException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Spring AI 的非流式摘要模型适配器，负责超时和供应商元数据归一化。 */
@Component
public class SpringAiChatSummaryModelClient
        implements ChatSummaryModelClient {

    private final ChatClient chatClient;
    private final ChatSummaryProperties properties;
    private final ExecutorService modelExecutor;

    public SpringAiChatSummaryModelClient(
            @Qualifier("summaryChatClient") ChatClient chatClient,
            ChatSummaryProperties properties,
            @Qualifier("chatSummaryModelExecutor")
            ExecutorService modelExecutor
    ) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.modelExecutor = modelExecutor;
    }

    /**
     * 在独立有界线程池中执行阻塞式模型调用，并为单次调用施加硬超时。
     * 超时或调用线程被中断时主动取消 Future，避免轮询 Worker 无限等待远端响应。
     */
    @Override
    public Response generate(Request request) {
        Objects.requireNonNull(request, "摘要模型请求不能为空");
        Future<Response> future;
        try {
            future = modelExecutor.submit(() -> invoke(request));
        } catch (RejectedExecutionException error) {
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.MODEL_CALL_FAILED,
                    "摘要模型调用线程已饱和"
            );
        }

        try {
            return future.get(
                    properties.timeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.MODEL_TIMEOUT,
                    "摘要模型调用超时"
            );
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.MODEL_CALL_FAILED,
                    "摘要模型调用被中断"
            );
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ChatSummaryGenerationException typed) {
                throw typed;
            }
            // 不附带第三方异常，避免其消息或请求转储把会话正文写入任务日志。
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.MODEL_CALL_FAILED,
                    "摘要模型调用失败"
            );
        }
    }

    /**
     * 发起一次 Spring AI 非流式请求。corrective=true 仅要求修正 JSON 格式，
     * 仍复用同一份受控输入，不允许模型通过纠偏阶段引入新的历史来源。
     */
    private Response invoke(Request request) {
        String instruction = request.corrective()
                ? "上次输出未通过结构校验。请仅根据同一输入重新输出严格 JSON，不要增加解释。\n"
                : "请将以下滚动历史压缩为结构化摘要 JSON。\n";
        ChatResponse response = chatClient.prompt()
                .system(request.systemPrompt())
                .user(instruction + request.inputJson())
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || !StringUtils.hasText(
                response.getResult().getOutput().getText())) {
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.MODEL_PROTOCOL_ERROR,
                    "摘要模型没有返回有效正文"
            );
        }

        // Token 用量允许供应商缺省；模型名缺失时回退到配置值，保证审计元数据仍可识别。
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String modelName = metadata == null
                || !StringUtils.hasText(metadata.getModel())
                ? properties.model()
                : metadata.getModel();
        return new Response(
                response.getResult().getOutput().getText(),
                modelName,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens()
        );
    }
}
