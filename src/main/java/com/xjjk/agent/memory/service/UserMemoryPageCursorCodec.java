package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Component
public class UserMemoryPageCursorCodec {
    private static final int MAX_ENCODED_LENGTH = 256;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public String encode(UserMemoryPageCursor cursor) {
        validate(cursor);
        String payload = FORMATTER.format(cursor.updatedAt()) + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public UserMemoryPageCursor decode(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = payload.lastIndexOf('|');
            if (separator <= 0 || separator != payload.indexOf('|')
                    || separator == payload.length() - 1) {
                throw invalid();
            }
            UserMemoryPageCursor cursor = new UserMemoryPageCursor(
                    LocalDateTime.parse(payload.substring(0, separator), FORMATTER),
                    Long.parseLong(payload.substring(separator + 1)));
            validate(cursor);
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
                || cursor.updatedAt().getNano() % 1_000_000 != 0) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ApiErrorCode.VALIDATION_ERROR);
    }
}
