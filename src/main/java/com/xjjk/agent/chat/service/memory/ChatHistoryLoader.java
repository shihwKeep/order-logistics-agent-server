package com.xjjk.agent.chat.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xjjk.agent.chat.config.ChatHistoryProperties;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.persistence.projection.ChatHistoryMessageMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 数据库历史快照加载服务。
 *
 * 在独立的可重复读事务内读取版本、消息元信息和正文。
 * 仅使用普通查询，不通过 FOR UPDATE 锁定会话。
 * 事务内禁止访问 Redis、调用模型或生成摘要。
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryLoader {

    private final AgentMessageMapper messageMapper;
    private final ChatHistoryProperties properties;
    private final ChatHistorySelector selector;

    /**
     * 根据已经验证的稳定游标加载历史。
     *
     * 必须通过 Spring 注入的 Bean 调用。
     * 游标必须来自 ChatHistoryCursorLoader，不能由前端构造。
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            timeout = 5,
            rollbackFor = Exception.class
    )
    public ChatHistorySnapshot load(ChatHistoryCursor cursor) {
        Objects.requireNonNull(cursor, "稳定历史游标不能为空");

        int scanLimit = properties.maxScanMessages();

        List<ChatHistoryMessageMetadata> rows =
                messageMapper.selectHistoryMetadata(
                        cursor.tenantId(),
                        cursor.userId(),
                        cursor.conversationId(),
                        cursor.beforeSequence(),
                        scanLimit + 1
                );

        boolean hasEarlierMessages = rows.size() > scanLimit;

        // 去掉额外探测行，仅对本次候选范围进行筛选。
        List<ChatHistoryMessageMetadata> candidates =
                rows.subList(0, Math.min(rows.size(), scanLimit));

        ChatHistorySelector.Selection selection = selector.select(
                candidates,
                properties.maxReadBytes()
        );

        List<ChatHistoryTurn> turns = readSelectedTurns(
                cursor,
                selection
        );

        return new ChatHistorySnapshot(
                cursor.tenantId(),
                cursor.userId(),
                cursor.conversationId(),
                cursor.memoryVersion(),
                cursor.memoryUntilSequence(),
                cursor.beforeSequence(),
                turns,
                hasEarlierMessages,
                selection.readBudgetTruncated()
        );
    }

    /**
     * 只读取筛选器选中的正文，并再次校验结构。
     */
    private List<ChatHistoryTurn> readSelectedTurns(
            ChatHistoryCursor cursor,
            ChatHistorySelector.Selection selection
    ) {
        List<String> messageIds = selection.messageIds();

        // 必须提前返回，避免空 IN 条件被省略后扩大查询范围。
        if (messageIds.isEmpty()) {
            return List.of();
        }

        List<AgentMessageEntity> messages = messageMapper.selectList(
                Wrappers.<AgentMessageEntity>lambdaQuery()
                        .select(
                                AgentMessageEntity::getMessageId,
                                AgentMessageEntity::getRequestId,
                                AgentMessageEntity::getMessageSequence,
                                AgentMessageEntity::getRole,
                                AgentMessageEntity::getStatus,
                                AgentMessageEntity::getContent
                        )
                        .eq(AgentMessageEntity::getTenantId,
                                cursor.tenantId())
                        .eq(AgentMessageEntity::getUserId, cursor.userId())
                        .eq(AgentMessageEntity::getConversationId,
                                cursor.conversationId())
                        .lt(AgentMessageEntity::getMessageSequence,
                                cursor.beforeSequence())
                        .in(AgentMessageEntity::getMessageId, messageIds)
                        .orderByAsc(AgentMessageEntity::getMessageSequence)
        );

        if (messages.size() != messageIds.size()
                || messages.size() % 2 != 0) {
            throw new IllegalStateException("历史正文查询结果数量不一致");
        }

        long actualBytes = 0L;

        for (int i = 0; i < messages.size(); i++) {
            AgentMessageEntity message = messages.get(i);

            if (!messageIds.get(i).equals(message.getMessageId())
                    || !MessageStatus.SUCCESS.name().equals(message.getStatus())
                    || !StringUtils.hasText(message.getContent())) {
                throw new IllegalStateException("历史正文与筛选结果不一致");
            }

            actualBytes = Math.addExact(
                    actualBytes,
                    message.getContent().getBytes(StandardCharsets.UTF_8).length
            );
        }

        // 数据库为 utf8mb4，同一快照内前后读取的正文大小应一致。
        if (actualBytes != selection.contentBytes()
                || actualBytes > properties.maxReadBytes()) {
            throw new IllegalStateException("历史正文读取预算校验失败");
        }

        List<ChatHistoryTurn> turns = new ArrayList<>(messages.size() / 2);

        for (int i = 0; i < messages.size(); i += 2) {
            AgentMessageEntity user = messages.get(i);
            AgentMessageEntity assistant = messages.get(i + 1);

            if (!MessageRole.USER.name().equals(user.getRole())
                    || !MessageRole.ASSISTANT.name().equals(assistant.getRole())
                    || !Objects.equals(
                    user.getRequestId(), assistant.getRequestId())
                    || assistant.getMessageSequence()
                    != Math.addExact(user.getMessageSequence(), 1L)) {
                throw new IllegalStateException("历史正文轮次结构异常");
            }

            turns.add(new ChatHistoryTurn(
                    user.getRequestId(),
                    user.getMessageSequence(),
                    assistant.getMessageSequence(),
                    user.getContent(),
                    assistant.getContent()
            ));
        }

        return List.copyOf(turns);
    }
}
