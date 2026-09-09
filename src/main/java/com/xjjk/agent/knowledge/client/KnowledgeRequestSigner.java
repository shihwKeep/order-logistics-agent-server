package com.xjjk.agent.knowledge.client;

import com.xjjk.agent.knowledge.config.KnowledgeIntegrationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 生成与 Knowledge Service 完全一致的 HMAC 请求签名。 */
@Component
public class KnowledgeRequestSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PATH = "/api/v1/internal/knowledge/retrieve";

    private final byte[] secret;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    @Autowired
    public KnowledgeRequestSigner(KnowledgeIntegrationProperties properties) {
        this(properties.getInternalSecret(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    KnowledgeRequestSigner(String secret, Clock clock, Supplier<String> nonceSupplier) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("知识检索内部签名密钥至少需要32个字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    public SignedHeaders sign(
            long tenantId, long userId, String question, List<Long> knowledgeBaseIds) {
        long timestamp = clock.millis();
        String nonce = nonceSupplier.get();
        String canonical = canonical(
                tenantId, userId, timestamp, nonce, question, knowledgeBaseIds);
        return new SignedHeaders(timestamp, nonce, hmac(canonical));
    }

    static String canonical(
            long tenantId, long userId, long timestamp, String nonce, String question,
            List<Long> knowledgeBaseIds) {
        String ids = knowledgeBaseIds == null ? "" : knowledgeBaseIds.stream()
                .distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return "POST\n" + PATH + "\n" + tenantId + "\n" + userId + "\n"
                + timestamp + "\n" + nonce + "\n" + sha256(question.trim()) + "\n" + ids;
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算知识检索请求签名", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算知识检索问题摘要", exception);
        }
    }

    public record SignedHeaders(long timestamp, String nonce, String signature) {}
}
