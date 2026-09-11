package com.xjjk.agent.chat.service.conversation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleMigrationContractTest {

    @Test
    void backfillsOnlyDefaultTitledConversationsThatHaveASuccessfulAnswer() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("DEFAULT '新会话'")
                .contains("conversation.title IN ('新对话', '新会话')")
                .contains("assistant_message.role = 'ASSISTANT'")
                .contains("assistant_message.status = 'SUCCESS'")
                .contains("earlier_message.message_sequence < user_message.message_sequence");
    }

    @Test
    void normalizesWhitespaceAndTruncatesTitlesByFifteenCharacters() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("REGEXP_REPLACE(TRIM(user_message.content), '[[:space:]]+', ' ')")
                .contains("CHAR_LENGTH(first_question.normalized_content) > 15")
                .contains("LEFT(first_question.normalized_content, 15), '...'")
                .contains("first_question.normalized_content <> ''");
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V9__backfill_conversation_titles.sql")) {
            assertThat(stream).as("V9 conversation title migration").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
