package com.xjjk.agent.chat.service.memory;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 基于固定 Qwen 分词资源的纯文本 Token 估算器。
 *
 * 不包含角色标记、消息边界、工具定义等额外开销。
 * 结果用于上下文预算，不代表云端模型的实际计费用量。
 */
@Component
public class QwenTextTokenEstimator {

    /** 标识当前分词资源及估算策略，便于排查计数差异。 */
    private static final String STRATEGY_VERSION =
            "qwen3-reference-v1";

    /** 由 Spring 管理生命周期的本地分词器。 */
    private final HuggingFaceTokenizer tokenizer;

    public QwenTextTokenEstimator(
            @Qualifier("qwenReferenceTokenizer")
            HuggingFaceTokenizer tokenizer
    ) {
        this.tokenizer = tokenizer;
    }

    /**
     * 估算纯文本的 Token 数。
     *
     * 不裁剪、不去除空白，不修改输入文本。
     * 分词失败时向上抛出异常，不返回 0 掩盖故障。
     *
     * @param text 待估算文本，不能为空引用
     * @return 当前参考分词器得到的 Token 数
     */
    public long estimate(String text) {
        Objects.requireNonNull(text, "待估算文本不能为 null");

        if (text.isEmpty()) {
            return 0L;
        }

        int tokenCount = tokenizer.encode(text).getIds().length;

        if (tokenCount <= 0) {
            throw new IllegalStateException(
                    "非空文本的分词结果异常"
            );
        }

        return tokenCount;
    }

    /**
     * 获取估算策略版本，不包含任何业务正文。
     */
    public String strategyVersion() {
        return STRATEGY_VERSION;
    }
}
