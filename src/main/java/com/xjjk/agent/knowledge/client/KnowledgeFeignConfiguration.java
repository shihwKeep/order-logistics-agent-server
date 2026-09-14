package com.xjjk.agent.knowledge.client;

import feign.Retryer;
import feign.Request;
import feign.codec.ErrorDecoder;
import com.xjjk.agent.knowledge.config.KnowledgeIntegrationProperties;
import org.springframework.context.annotation.Bean;

/** 禁用 Feign 隐式重试，避免使用同一 nonce 重放内部签名请求。 */
public class KnowledgeFeignConfiguration {
    @Bean
    ErrorDecoder knowledgeErrorDecoder() {
        return new KnowledgeFeignErrorDecoder();
    }

    @Bean
    Retryer knowledgeRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    Request.Options knowledgeRequestOptions(KnowledgeIntegrationProperties properties) {
        return new Request.Options(
                properties.getConnectTimeout(), properties.getReadTimeout(), false);
    }
}
