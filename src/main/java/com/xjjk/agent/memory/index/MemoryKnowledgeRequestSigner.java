package com.xjjk.agent.memory.index;

import com.xjjk.agent.knowledge.config.KnowledgeIntegrationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 生成用户记忆内部索引、召回接口使用的路径绑定 HMAC 签名。 */
@Component
public class MemoryKnowledgeRequestSigner {
    public static final String INDEX_PATH =
            "/api/v1/internal/user-memories/index-events";
    public static final String RETRIEVE_PATH =
            "/api/v1/internal/user-memories/retrieve";

    private static final String ALGORITHM = "HmacSHA256";
    private static final Set<String> ALLOWED_PATHS = Set.of(INDEX_PATH, RETRIEVE_PATH);

    private final byte[] secret;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    @Autowired
    public MemoryKnowledgeRequestSigner(KnowledgeIntegrationProperties properties) {
        this(properties.getInternalSecret(), Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    MemoryKnowledgeRequestSigner(
            String secret, Clock clock, Supplier<String> nonceSupplier) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("用户记忆内部签名密钥至少需要32个字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.nonceSupplier = java.util.Objects.requireNonNull(nonceSupplier, "nonceSupplier");
    }

    public SignedHeaders sign(
            String path, long tenantId, long userId, String payloadDigest) {
        if (!ALLOWED_PATHS.contains(path) || tenantId <= 0 || userId <= 0
                || payloadDigest == null || payloadDigest.isBlank()) {
            throw new IllegalArgumentException("用户记忆内部签名参数不合法");
        }
        long timestamp = clock.millis();
        String nonce = nonceSupplier.get();
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("用户记忆内部签名 nonce 不能为空");
        }
        return new SignedHeaders(timestamp, nonce,
                hmac(canonical(path, tenantId, userId, timestamp, nonce, payloadDigest)));
    }

    static String canonical(
            String path, long tenantId, long userId, long timestamp,
            String nonce, String payloadDigest) {
        return "POST\n" + path + "\n" + tenantId + "\n" + userId + "\n"
                + timestamp + "\n" + nonce + "\n" + payloadDigest;
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算用户记忆内部请求签名", exception);
        }
    }

    public record SignedHeaders(long timestamp, String nonce, String signature) {
    }
}
