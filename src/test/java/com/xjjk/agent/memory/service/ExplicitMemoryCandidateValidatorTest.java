package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitMemoryCandidateValidatorTest {

    private final ExplicitMemoryCandidateValidator validator = new ExplicitMemoryCandidateValidator(
            new MemorySensitiveContentPolicy(new SensitiveContentSanitizer()),
            512,
            512
    );

    @Test
    void normalizesAndAcceptsMatchingCandidate() {
        ExplicitMemoryCandidate candidate = new ExplicitMemoryCandidate(
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                "preference.answer_style",
                "  用户偏好简洁回答  ",
                "以后回答简短一些",
                MemoryRetentionType.NORMAL
        );

        assertThat(validator.validate(candidate, "请记住以后回答简短一些", false))
                .isEqualTo(new ExplicitMemoryCandidate(
                        MemoryCategory.PREFERENCE_ANSWER_STYLE,
                        "preference.answer_style",
                        "用户偏好简洁回答",
                        "以后回答简短一些",
                        MemoryRetentionType.NORMAL
                ));
    }

    @Test
    void rejectsInvalidKeyEvidenceLengthAndRetention() {
        assertRejected(candidate("profile.preferred_name", "以后回答简短一些", "用户偏好简洁回答"), false);
        assertRejected(candidate("preference.answer_style", "不在原文", "用户偏好简洁回答"), false);
        assertRejected(candidate("preference.answer_style", "以后回答简短一些", "好".repeat(513)), false);
        assertRejected(new ExplicitMemoryCandidate(
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                "preference.answer_style",
                "用户偏好简洁回答",
                "以后回答简短一些",
                MemoryRetentionType.PERMANENT), false);
    }

    @Test
    void rejectsCredentialsAndSensitiveBusinessOrPersonalData() {
        for (String forbidden : new String[]{
                "Bearer abcdefghijklmnop",
                "身份证 320311199001011234",
                "手机号 13800138000",
                "银行卡 6222021234567890123",
                "诊断为高血压",
                "订单号 JTS012020240101",
                "退款记录已完成",
                "物流单号 SF1234567890"
        }) {
            assertRejected(candidate("preference.answer_style", forbidden, forbidden), false, "请记住" + forbidden);
        }
    }

    private ExplicitMemoryCandidate candidate(String key, String evidence, String content) {
        return new ExplicitMemoryCandidate(
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                key,
                content,
                evidence,
                MemoryRetentionType.NORMAL
        );
    }

    private void assertRejected(ExplicitMemoryCandidate candidate, boolean permanent) {
        assertRejected(candidate, permanent, "请记住以后回答简短一些");
    }

    private void assertRejected(ExplicitMemoryCandidate candidate, boolean permanent, String original) {
        assertThatThrownBy(() -> validator.validate(candidate, original, permanent))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
