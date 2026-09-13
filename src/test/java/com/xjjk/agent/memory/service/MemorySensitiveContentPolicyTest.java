package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySensitiveContentPolicyTest {

    private final MemorySensitiveContentPolicy policy =
            new MemorySensitiveContentPolicy(new SensitiveContentSanitizer());

    @Test
    void rejectsPrivacyIdentifiersRegardlessOfFormatting() {
        assertThat(policy.isAllowed("+86 (138) 0013.8000")).isFalse();
        assertThat(policy.isAllowed("＋８６（１３８）００１３．８０００")).isFalse();
        assertThat(policy.isAllowed("138.0013.8000")).isFalse();
        assertThat(policy.isAllowed("320.311.1990 0101-123X")).isFalse();
        assertThat(policy.isAllowed("6222(0212)3456.7890-123")).isFalse();
    }

    @Test
    void rejectsExpandedExactAddressForms() {
        assertThat(policy.isAllowed("我家在浦东新区世纪大道100号")).isFalse();
        assertThat(policy.isAllowed("居住地是浦东新区世纪大道100号")).isFalse();
        assertThat(policy.isAllowed("现住址浦东新区世纪大道100号")).isFalse();
        assertThat(policy.isAllowed("住宅地址为浦东新区世纪大道100号")).isFalse();
    }

    @Test
    void keepsOrdinaryNumbersAndProfileValuesAllowed() {
        assertThat(policy.isAllowed("32岁")).isTrue();
        assertThat(policy.isAllowed("编号32")).isTrue();
        assertThat(policy.isAllowed("杭州")).isTrue();
        assertThat(policy.isAllowed("架构师")).isTrue();
    }
}
