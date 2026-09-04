package com.xjjk.agent.chat.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.api.dto.ChatMessagePageResponse;
import com.xjjk.agent.chat.api.dto.ChatMessageResponse;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 历史消息查询服务。
 *
 * 先校验会话归属，再按照消息序号向前分页。
 * 返回所有状态的消息，不仅限于成功消息。
 */
@Service
@RequiredArgsConstructor
public class AgentMessageQueryService {

    /** 单次查询允许返回的最大消息条数。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 接口展示时间使用的时区。 */
    private static final ZoneId DISPLAY_ZONE =
            ZoneId.of("Asia/Shanghai");

    /** 会话归属校验服务。 */
    private final AgentConversationService conversationService;

    /** 消息数据访问接口。 */
    private final AgentMessageMapper messageMapper;

    /**
     * 分页查询当前用户的历史消息。
     *
     * @param conversationId 会话 ID
     * @param identity 后端认证得到的用户身份
     * @param beforeSequence 只查询此序号之前的消息；为空时查询最新一页
     * @param pageSize 本页最多返回的消息条数，范围为 1～100
     */
    public ChatMessagePageResponse queryPage(
            String conversationId,
            AgentIdentity identity,
            Long beforeSequence,
            int pageSize
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        if (beforeSequence != null && beforeSequence < 1) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        // 即使会话没有消息，也必须先校验归属。
        // 不存在的会话与无权访问的会话，对外使用相同错误。
        conversationService.requireOwned(conversationId, identity);

        // 多查询一条，仅用于判断是否还有更早的消息。
        int queryLimit = pageSize + 1;

        List<AgentMessageEntity> rows = messageMapper.selectList(
                Wrappers.<AgentMessageEntity>lambdaQuery()
                        .eq(
                                AgentMessageEntity::getTenantId,
                                identity.tenantId()
                        )
                        .eq(
                                AgentMessageEntity::getUserId,
                                identity.userId()
                        )
                        .eq(
                                AgentMessageEntity::getConversationId,
                                conversationId
                        )
                        .lt(
                                beforeSequence != null,
                                AgentMessageEntity::getMessageSequence,
                                beforeSequence
                        )
                        .orderByDesc(
                                AgentMessageEntity::getMessageSequence
                        )
                        // 这里只拼接经过范围校验的整数，
                        // 不允许拼接前端传入的 SQL 片段。
                        .last("LIMIT " + queryLimit)
        );

        boolean hasMore = rows.size() > pageSize;

        // 去掉用于探测下一页的额外消息，并复制为可修改列表。
        List<AgentMessageEntity> page = new ArrayList<>(
                rows.subList(0, Math.min(rows.size(), pageSize))
        );

        // 当前仍是倒序，最后一条就是本页最小序号。
        // 下一页使用本页保留的消息作为边界，不能使用额外探测行。
        Long nextBeforeSequence = hasMore
                ? page.get(page.size() - 1).getMessageSequence()
                : null;

        // 查询时优先取最新消息，返回时恢复为从旧到新的阅读顺序。
        Collections.reverse(page);

        List<ChatMessageResponse> items = page.stream()
                .map(this::toResponse)
                .toList();

        return new ChatMessagePageResponse(
                items,
                nextBeforeSequence,
                hasMore
        );
    }

    /**
     * 将数据库消息转换为接口响应。
     */
    private ChatMessageResponse toResponse(AgentMessageEntity message) {
        return new ChatMessageResponse(
                message.getMessageId(),
                message.getRequestId(),
                message.getMessageSequence(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getFinishReason(),
                message.getErrorCode(),
                // 数据库存储的是 UTC 时间，展示时转换为上海时间。
                message.getCreatedAt()
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(DISPLAY_ZONE)
                        .toOffsetDateTime()
        );
    }
}