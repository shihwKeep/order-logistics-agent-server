package com.xjjk.agent.chat.domain.summary;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSummaryDomainContractTest {

    @Test
    void shouldRejectBlankFactContent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummaryFact(
                        " ",
                        ChatSummarySourceType.USER_MESSAGE,
                        1L
                ));
    }

    @Test
    void shouldRejectUnsupportedSourceTypeAndInvalidSequence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummaryFact(
                        "用户提出问题",
                        null,
                        1L
                ));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummaryItem("待确认问题", 0L));
    }

    @Test
    void shouldDefensivelyCopyContentCollections() {
        List<ChatSummaryFact> facts = new ArrayList<>();
        facts.add(new ChatSummaryFact(
                "用户自述需要查询商品",
                ChatSummarySourceType.USER_MESSAGE,
                1L
        ));

        ChatSummaryContent content = new ChatSummaryContent(
                1,
                "商品咨询",
                "等待商品系统接入",
                facts,
                List.of(),
                List.of(),
                List.of()
        );
        facts.clear();

        assertThat(content.conversationFacts()).hasSize(1);
        assertThatThrownBy(() -> content.conversationFacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectSnapshotWhoseCoverageExceedsCapturedBoundary() {
        ChatSummaryContent content = new ChatSummaryContent(
                1,
                "商品咨询",
                "等待处理",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummarySnapshot(
                        1L,
                        10567L,
                        "conversation-1",
                        1L,
                        6L,
                        2L,
                        4L,
                        content,
                        "conversation-summary-v1",
                        "qwen-plus"
                ));
    }

    @Test
    void shouldCreateMetadataOnlySnapshotString() {
        ChatSummarySnapshot snapshot = new ChatSummarySnapshot(
                1L,
                10567L,
                "conversation-1",
                1L,
                4L,
                2L,
                4L,
                new ChatSummaryContent(
                        1,
                        "敏感主题",
                        "敏感状态",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                ),
                "conversation-summary-v1",
                "qwen-plus"
        );

        assertThat(snapshot.toString())
                .contains("summaryVersion=1", "coveredUntilSequence=4")
                .doesNotContain("敏感主题", "敏感状态");
    }

    @Test
    void shouldKeepAllSummaryValueStringsMetadataOnly() {
        ChatSummaryFact fact = new ChatSummaryFact(
                "敏感事实",
                ChatSummarySourceType.USER_MESSAGE,
                1
        );
        ChatSummaryItem item = new ChatSummaryItem("敏感问题", 1);
        ChatSummaryEntity entity = new ChatSummaryEntity(
                "SECRET_TYPE",
                "敏感实体值",
                ChatSummarySourceType.USER_MESSAGE,
                1
        );
        ChatSummaryContent content = new ChatSummaryContent(
                1,
                "敏感主题",
                "敏感状态",
                List.of(fact),
                List.of(item),
                List.of(),
                List.of(entity)
        );

        assertThat(fact.toString()).doesNotContain("敏感事实");
        assertThat(item.toString()).doesNotContain("敏感问题");
        assertThat(entity.toString())
                .doesNotContain("SECRET_TYPE", "敏感实体值");
        assertThat(content.toString())
                .doesNotContain("敏感主题", "敏感状态", "敏感事实");
    }
}
