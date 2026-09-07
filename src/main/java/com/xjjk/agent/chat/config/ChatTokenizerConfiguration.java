package com.xjjk.agent.chat.config;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 本地 Qwen 参考分词器配置。
 *
 * 资源随应用发布，启动时校验完整性。
 * 仅提供分词能力，不加载模型权重，不执行模型推理。
 * 分词结果仍需与云端 qwen-plus 的实际用量进行对照。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ChatTokenizerConfiguration {

    /** 固定版本的本地分词资源。 */
    private static final String RESOURCE_PATH =
            "tokenizer/qwen3-reference-v1/tokenizer.json";

    /** 经官方资源页面核对的文件摘要。 */
    private static final String EXPECTED_SHA256 =
            "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4";

    /** 防止误放入异常大的资源文件；当前文件约 11.4 MB。 */
    private static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;

    @Bean(destroyMethod = "close")
    public HuggingFaceTokenizer qwenReferenceTokenizer()
            throws IOException, NoSuchAlgorithmException {

        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);

        byte[] resourceBytes;

        // 使用流读取，兼容 IDEA 和打包后的 JAR。
        try (InputStream input = resource.getInputStream()) {
            resourceBytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
        }

        if (resourceBytes.length > MAX_RESOURCE_BYTES) {
            throw new IllegalStateException("分词资源超过允许的大小");
        }

        String actualSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(resourceBytes)
        );

        if (!EXPECTED_SHA256.equals(actualSha256)) {
            throw new IllegalStateException("分词资源 SHA-256 校验失败");
        }

        Map<String, String> options = Map.of(
                "addSpecialTokens", "false",
                "truncation", "false",
                "padding", "false",
                "withOverflowingTokens", "false"
        );

        HuggingFaceTokenizer tokenizer;

        try (InputStream input =
                     new ByteArrayInputStream(resourceBytes)) {
            tokenizer = HuggingFaceTokenizer.newInstance(input, options);
        }

        boolean initialized = false;

        try {
            verifyTokenizer(tokenizer);
            initialized = true;
            return tokenizer;
        } finally {
            // Bean 尚未成功返回时，Spring 不能替我们管理这个实例。
            if (!initialized) {
                tokenizer.close();
            }
        }
    }

    /**
     * 使用固定、无敏感信息的样本验证本地分词能力。
     *
     * 只验证当前样本的编码完整性，不证明与云端模型计数完全一致。
     */
    private void verifyTokenizer(HuggingFaceTokenizer tokenizer) {
        String shortText = "订单 A123，商品 Apple，数量 2，配送中🚚。";
        long[] shortIds = tokenizer.encode(shortText).getIds();

        if (shortIds.length == 0
                || !shortText.equals(tokenizer.decode(shortIds, false))) {
            throw new IllegalStateException("分词器短文本编码自检失败");
        }

        String longText = shortText.repeat(200);
        long[] longIds = tokenizer.encode(longText).getIds();

        if (longIds.length <= 512) {
            throw new IllegalStateException(
                    "长文本分词结果异常，可能存在截断或配置问题"
            );
        }

        if (!longText.equals(tokenizer.decode(longIds, false))) {
            throw new IllegalStateException(
                    "长文本分词后无法完整还原，可能存在截断或文本变换"
            );
        }

        log.info(
                "chat_tokenizer_self_check strategy={}, shortTokens={}, "
                        + "longTokens={}, roundTrip=true",
                "qwen3-reference-v1",
                shortIds.length,
                longIds.length
        );
    }
}
