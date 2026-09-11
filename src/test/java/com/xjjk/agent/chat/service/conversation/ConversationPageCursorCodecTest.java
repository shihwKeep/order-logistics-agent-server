package com.xjjk.agent.chat.service.conversation;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationPageCursorCodecTest {
    private final ConversationPageCursorCodec codec =
            new ConversationPageCursorCodec();

    @Test
    void roundTripsAMillisecondPrecisionCursor() {
        ConversationPageCursor cursor = new ConversationPageCursor(
                LocalDateTime.of(2026, 9, 11, 5, 17, 26, 722_000_000),
                23L
        );

        assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
    }

    @Test
    void rejectsInvalidCursorValues() {
        assertInvalid(null);
        assertInvalid(" ");
        assertInvalid("not-base64!");
        assertInvalid("a".repeat(257));
        assertInvalid(encoded("2026-09-11T05:17:26.722|0"));
        assertInvalid(encoded("2026-09-11T05:17:26.722123|23"));
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> codec.decode(value))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ApiErrorCode.VALIDATION_ERROR));
    }

    private String encoded(String payload) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
