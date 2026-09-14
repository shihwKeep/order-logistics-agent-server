package com.xjjk.agent.chat.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamRequestContractTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void rejectsMissingClientRequestId() {
        ChatStreamRequest request = new ChatStreamRequest(
                null,
                "你好",
                null,
                null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("请求ID不能为空");
    }

    @Test
    void rejectsMalformedClientRequestId() {
        ChatStreamRequest request = new ChatStreamRequest(
                null,
                "你好",
                null,
                "not-a-uuid");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("请求ID格式不合法");
    }

    @Test
    void acceptsVersionFourUuid() {
        ChatStreamRequest request = new ChatStreamRequest(
                null,
                "你好",
                null,
                "6f899318-0af5-4f2b-a593-84f6dac9dd1c");

        assertThat(validator.validate(request)).isEmpty();
    }
}
