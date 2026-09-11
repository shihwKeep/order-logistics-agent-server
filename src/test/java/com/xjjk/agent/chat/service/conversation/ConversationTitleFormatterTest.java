package com.xjjk.agent.chat.service.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleFormatterTest {

    @Test
    void normalizesWhitespaceAndPreservesShortQuestion() {
        assertThat(ConversationTitleFormatter.fromQuestion(
                "  第一行\n第二行   第三行  "))
                .isEqualTo("第一行 第二行 第三行");
    }

    @Test
    void truncatesAfterFifteenUnicodeCodePoints() {
        assertThat(ConversationTitleFormatter.fromQuestion(
                "员工😀请假需要提前多久提交申请单据"))
                .isEqualTo("员工😀请假需要提前多久提交申请...");
        assertThat(ConversationTitleFormatter.fromQuestion(
                "123456789012345"))
                .isEqualTo("123456789012345");
        assertThat(ConversationTitleFormatter.fromQuestion(
                "1234567890123456"))
                .isEqualTo("123456789012345...");
    }

    @Test
    void usesDefaultForMissingOrBlankQuestion() {
        assertThat(ConversationTitleFormatter.fromQuestion(null))
                .isEqualTo("新会话");
        assertThat(ConversationTitleFormatter.fromQuestion(""))
                .isEqualTo("新会话");
        assertThat(ConversationTitleFormatter.fromQuestion(" \n\t "))
                .isEqualTo("新会话");
    }
}
