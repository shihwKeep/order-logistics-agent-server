package com.xjjk.agent.chat.service.conversation;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** Encodes internal pagination boundaries as opaque URL-safe values. */
@Component
public class ConversationPageCursorCodec {
    private static final int MAX_ENCODED_LENGTH = 256;
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public String encode(ConversationPageCursor cursor) {
        validate(cursor);
        String payload = DATE_TIME_FORMATTER.format(cursor.updatedAt())
                + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    public ConversationPageCursor decode(String value) {
        if (value == null || value.isBlank()
                || value.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8
            );
            int separator = payload.lastIndexOf('|');
            if (separator <= 0 || separator == payload.length() - 1
                    || payload.indexOf('|') != separator) {
                throw invalid();
            }
            ConversationPageCursor cursor = new ConversationPageCursor(
                    LocalDateTime.parse(
                            payload.substring(0, separator),
                            DATE_TIME_FORMATTER
                    ),
                    Long.parseLong(payload.substring(separator + 1))
            );
            validate(cursor);
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private void validate(ConversationPageCursor cursor) {
        if (cursor == null || cursor.updatedAt() == null
                || cursor.id() <= 0
                || cursor.updatedAt().getNano() % 1_000_000 != 0) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ApiErrorCode.VALIDATION_ERROR);
    }
}
