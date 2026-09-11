package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryCursorProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Component
public class UserMemoryPageCursorCodec {
    private static final int MAX_ENCODED_LENGTH = 256;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public UserMemoryPageCursorCodec(UserMemoryCursorProperties properties) {
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public String encode(UserMemoryPageCursor cursor) {
        validate(cursor);
        String payload = "v1|" + FORMATTER.format(cursor.updatedAt()) + "|" + cursor.id()
                + "|" + cursor.tenantId() + "|" + cursor.userId() + "|" + cursor.generation();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(sign(payloadBytes));
    }

    public UserMemoryPageCursor decode(String value, long expectedTenantId,
                                       long expectedUserId, long expectedGeneration) {
        if (value == null || value.isBlank() || value.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String[] tokenParts = value.split("\\.", -1);
            if (tokenParts.length != 2 || tokenParts[0].isBlank() || tokenParts[1].isBlank()) {
                throw invalid();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(tokenParts[0]);
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(tokenParts[1]);
            if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
                throw invalid();
            }
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 6 || !"v1".equals(fields[0])) {
                throw invalid();
            }
            UserMemoryPageCursor cursor = new UserMemoryPageCursor(
                    LocalDateTime.parse(fields[1], FORMATTER),
                    Long.parseLong(fields[2]),
                    Long.parseLong(fields[3]),
                    Long.parseLong(fields[4]),
                    Long.parseLong(fields[5]));
            validate(cursor);
            if (cursor.tenantId() != expectedTenantId || cursor.userId() != expectedUserId
                    || cursor.generation() != expectedGeneration) {
                throw invalid();
            }
            return cursor;
        } catch (RuntimeException exception) {
            if (exception instanceof BusinessException business) {
                throw business;
            }
            throw invalid();
        }
    }

    private void validate(UserMemoryPageCursor cursor) {
        if (cursor == null || cursor.updatedAt() == null || cursor.id() <= 0
                || cursor.tenantId() <= 0 || cursor.userId() <= 0 || cursor.generation() <= 0
                || cursor.updatedAt().getNano() % 1_000_000 != 0) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("无法签名用户记忆分页游标", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private BusinessException invalid() {
        return new BusinessException(ApiErrorCode.VALIDATION_ERROR);
    }
}
