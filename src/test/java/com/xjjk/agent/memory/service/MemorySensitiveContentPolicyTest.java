package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySensitiveContentPolicyTest {

    private final MemorySensitiveContentPolicy policy =
            new MemorySensitiveContentPolicy(new SensitiveContentSanitizer());

    @Test
    void rejectsSeparatedChinesePhoneIdentityAndBankNumbers() {
        assertThat(policy.isAllowed("138-0013-8000")).isFalse();
        assertThat(policy.isAllowed("320 311 1990 0101 1234")).isFalse();
        assertThat(policy.isAllowed("6222 0212 3456 7890 123")).isFalse();
    }

    @Test
    void keepsOrdinaryNumbersAndProfileValuesAllowed() {
        assertThat(policy.isAllowed("32岁")).isTrue();
        assertThat(policy.isAllowed("杭州")).isTrue();
        assertThat(policy.isAllowed("架构师")).isTrue();
    }
}
