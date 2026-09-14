package com.xjjk.agent.knowledge.client;

import com.xjjk.agent.knowledge.service.KnowledgeModelBudgetExceededException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeFeignErrorDecoderTest {
    @Test
    void preservesStableBudgetErrorWithoutProviderDetails() {
        Response response = Response.builder().status(503).reason("Service Unavailable")
                .request(Request.create(Request.HttpMethod.POST, "http://knowledge/retrieve",
                        Map.of(), null, StandardCharsets.UTF_8, null))
                .body("{\"code\":\"KNOWLEDGE_MODEL_BUDGET_EXHAUSTED\",\"message\":\"secret\"}",
                        StandardCharsets.UTF_8)
                .build();

        Exception exception = new KnowledgeFeignErrorDecoder().decode("retrieve", response);

        assertThat(exception).isInstanceOf(KnowledgeModelBudgetExceededException.class)
                .hasMessage("知识检索模型本月额度已用尽");
    }
}
