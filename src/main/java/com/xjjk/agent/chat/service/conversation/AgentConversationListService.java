package com.xjjk.agent.chat.service.conversation;

import com.xjjk.agent.chat.api.dto.ConversationListItemResponse;
import com.xjjk.agent.chat.api.dto.ConversationPageResponse;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.projection.ConversationListRow;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Lists non-empty conversations owned by the authenticated user. */
@Service
@RequiredArgsConstructor
public class AgentConversationListService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentConversationMapper conversationMapper;
    private final ConversationPageCursorCodec cursorCodec;

    public ConversationPageResponse list(
            AgentIdentity identity,
            String cursorValue,
            int pageSize
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        ConversationPageCursor cursor = cursorValue == null
                ? null : cursorCodec.decode(cursorValue);
        List<ConversationListRow> rows =
                conversationMapper.selectOwnedConversationPage(
                        identity.tenantId(),
                        identity.userId(),
                        cursor == null ? null : cursor.updatedAt(),
                        cursor == null ? null : cursor.id(),
                        pageSize + 1
                );

        boolean hasMore = rows.size() > pageSize;
        List<ConversationListRow> visibleRows = hasMore
                ? rows.subList(0, pageSize) : rows;
        List<ConversationListItemResponse> items = visibleRows.stream()
                .map(this::toResponse)
                .toList();
        String nextCursor = hasMore
                ? cursorCodec.encode(new ConversationPageCursor(
                        visibleRows.getLast().updatedAt(),
                        visibleRows.getLast().id()
                ))
                : null;
        return new ConversationPageResponse(items, nextCursor, hasMore);
    }

    private ConversationListItemResponse toResponse(ConversationListRow row) {
        return new ConversationListItemResponse(
                row.conversationId(),
                row.title(),
                row.updatedAt()
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(DISPLAY_ZONE)
                        .toOffsetDateTime()
        );
    }
}
