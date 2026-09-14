package com.xjjk.agent.knowledge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.knowledge.service.KnowledgeModelBudgetExceededException;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.InputStream;

public class KnowledgeFeignErrorDecoder implements ErrorDecoder {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ErrorDecoder fallback = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response != null && response.body() != null) {
            try (InputStream input = response.body().asInputStream()) {
                JsonNode body = objectMapper.readTree(input);
                if (body != null && "KNOWLEDGE_MODEL_BUDGET_EXHAUSTED"
                        .equals(body.path("code").asText())) {
                    return new KnowledgeModelBudgetExceededException();
                }
            } catch (Exception ignored) {
                // Fall through to Feign's status-based exception without retaining response text.
            }
        }
        return fallback.decode(methodKey, response);
    }
}
