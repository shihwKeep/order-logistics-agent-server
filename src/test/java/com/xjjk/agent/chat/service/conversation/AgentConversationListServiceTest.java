package com.xjjk.agent.chat.service.conversation;

import com.xjjk.agent.chat.api.dto.ConversationPageResponse;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.projection.ConversationListRow;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationListServiceTest {
    @Mock
    private AgentConversationMapper mapper;

    private final ConversationPageCursorCodec codec =
            new ConversationPageCursorCodec();

    @Test
    void listsOwnedRowsWithOneExtraProbeAndReturnsNextCursor() {
        LocalDateTime boundary = LocalDateTime.of(
                2026, 9, 11, 5, 17, 26, 722_000_000);
        ConversationPageCursor incoming =
                new ConversationPageCursor(boundary, 100L);
        List<ConversationListRow> rows = rows(11);
        AgentIdentity identity = new AgentIdentity(
                74680L, "74680", "石海文", 7L, 1L);
        when(mapper.selectOwnedConversationPage(
                1L, 74680L, boundary, 100L, 11)).thenReturn(rows);

        ConversationPageResponse page = service().list(
                identity, codec.encode(incoming), 10);

        assertThat(page.items()).hasSize(10);
        assertThat(page.hasMore()).isTrue();
        ConversationListRow tenth = rows.get(9);
        assertThat(codec.decode(page.nextCursor())).isEqualTo(
                new ConversationPageCursor(tenth.updatedAt(), tenth.id()));
        assertThat(page.items().getFirst().updatedAt()).isEqualTo(
                OffsetDateTime.of(
                        rows.getFirst().updatedAt().plusHours(8),
                        ZoneOffset.ofHours(8)
                ));
        verify(mapper).selectOwnedConversationPage(
                1L, 74680L, boundary, 100L, 11);
    }

    @Test
    void returnsNullCursorForEmptyAndFinalPages() {
        AgentIdentity identity = new AgentIdentity(
                74680L, "74680", "石海文", 7L, 1L);
        when(mapper.selectOwnedConversationPage(
                1L, 74680L, null, null, 11))
                .thenReturn(List.of())
                .thenReturn(rows(3));

        ConversationPageResponse empty = service().list(identity, null, 10);
        ConversationPageResponse finalPage = service().list(identity, null, 10);

        assertThat(empty.items()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
        assertThat(empty.nextCursor()).isNull();
        assertThat(finalPage.items()).hasSize(3);
        assertThat(finalPage.hasMore()).isFalse();
        assertThat(finalPage.nextCursor()).isNull();
    }

    @Test
    void rejectsPageSizesOutsideThePublicRangeBeforeQuerying() {
        AgentIdentity identity = new AgentIdentity(
                74680L, "74680", "石海文", 7L, 1L);

        for (int invalid : List.of(0, 51)) {
            assertThatThrownBy(() -> service().list(identity, null, invalid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode())
                                    .isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        }
        verify(mapper, never()).selectOwnedConversationPage(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private AgentConversationListService service() {
        return new AgentConversationListService(mapper, codec);
    }

    private List<ConversationListRow> rows(int size) {
        List<ConversationListRow> rows = new ArrayList<>();
        LocalDateTime latest = LocalDateTime.of(
                2026, 9, 11, 6, 0, 0, 500_000_000);
        for (int index = 0; index < size; index++) {
            rows.add(new ConversationListRow(
                    100L - index,
                    "conversation-" + index,
                    "问题" + index,
                    latest.minusMinutes(index)
            ));
        }
        return rows;
    }
}
