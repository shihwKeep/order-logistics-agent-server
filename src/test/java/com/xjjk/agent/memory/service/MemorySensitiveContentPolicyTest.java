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
    void rejectsGovernmentIdentitySemanticsInChineseAndEnglish() {
        for (String sensitive : new String[]{
                "我的护照号是E12345678", "护照号码E12345678", "证件号A12345",
                "证件号码A12345", "驾驶证号A12345", "驾照号A12345",
                "社保号A12345", "社会保障号A12345", "税号A12345",
                "纳税人识别号A12345", "passport number E12345678", "SSN 123-45-6789",
                "social security number 123-45-6789", "national ID A12345",
                "driver license A12345", "tax ID A12345"}) {
            assertThat(policy.isAllowed(sensitive)).as(sensitive).isFalse();
        }
    }

    @Test
    void keepsOrdinaryNumbersAndProfileValuesAllowed() {
        assertThat(policy.isAllowed("32岁")).isTrue();
        assertThat(policy.isAllowed("编号32")).isTrue();
        assertThat(policy.isAllowed("杭州")).isTrue();
        assertThat(policy.isAllowed("架构师")).isTrue();
    }
}
