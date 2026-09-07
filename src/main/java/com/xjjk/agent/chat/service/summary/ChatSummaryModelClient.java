package com.xjjk.agent.chat.service.summary;

import org.springframework.util.StringUtils;

/** 摘要生成器依赖的窄模型边界，隔离 Spring AI 和供应商协议细节。 */
public interface ChatSummaryModelClient {

    Response generate(Request request);

    /**
     * 模型请求只传固定系统规则和已编码数据；corrective 只允许纠正输出格式。
     */
    record Request(
            String systemPrompt,
            String inputJson,
            boolean corrective
    ) {
        public Request {
            if (!StringUtils.hasText(systemPrompt)
                    || !StringUtils.hasText(inputJson)) {
                throw new IllegalArgumentException("摘要模型请求不能为空");
            }
        }

        @Override
        public String toString() {
            return "Request[corrective=" + corrective
                    + ", content=<redacted>]";
        }
    }

    /** 模型原始响应仅在生成器内短暂存在，禁止通过对象字符串泄露。 */
    record Response(
            String rawJson,
            String modelName,
            Integer inputTokens,
            Integer outputTokens
    ) {
        public Response {
            if (!StringUtils.hasText(rawJson)
                    || !StringUtils.hasText(modelName)
                    || inputTokens != null && inputTokens < 0
                    || outputTokens != null && outputTokens < 0) {
                throw new IllegalArgumentException("摘要模型响应元数据不合法");
            }
        }

        @Override
        public String toString() {
            return "Response[modelName=" + modelName
                    + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens
                    + ", content=<redacted>]";
        }
    }
}
