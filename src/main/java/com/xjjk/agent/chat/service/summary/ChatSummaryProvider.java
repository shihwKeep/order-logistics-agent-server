package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 当前请求使用的长期摘要提供器。
 *
 * 数据库查询始终携带租户、用户和会话三个归属条件；摘要坏值只影响本次
 * 摘要注入，不阻断已经通过权限校验的短期历史加载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSummaryProvider {

    private final AgentConversationSummaryMapper summaryMapper;
    private final ObjectMapper objectMapper;
    private final ChatSummaryProperties properties;

    public ChatSummarySnapshot load(ChatHistoryCursor cursor) {
        Objects.requireNonNull(cursor, "稳定历史游标不能为空");

        // 上下文开关关闭时完全不访问摘要表，允许先独立验证后台生成链路。
        if (!properties.contextEnabled()) {
            return ChatSummarySnapshot.empty(cursor);
        }

        AgentConversationSummaryEntity entity;
        try {
            entity = summaryMapper.selectOwned(
                    cursor.tenantId(),
                    cursor.userId(),
                    cursor.conversationId()
            );
        } catch (SecurityException exception) {
            // 租户拦截器或 Mapper 报告的权限异常必须向上传播。
            throw exception;
        } catch (RuntimeException exception) {
            return failOpen(cursor, "DATABASE_READ", exception);
        }

        if (entity == null) {
            return ChatSummarySnapshot.empty(cursor);
        }

        try {
            validateMetadata(cursor, entity);
            ChatSummaryContent content = objectMapper.readValue(
                    entity.getContentJson(),
                    ChatSummaryContent.class
            );
            if (content.schemaVersion() != entity.getSchemaVersion()) {
                throw new IllegalArgumentException("摘要正文与记录结构版本不一致");
            }
            return new ChatSummarySnapshot(
                    entity.getTenantId(),
                    entity.getUserId(),
                    entity.getConversationId(),
                    entity.getSummaryVersion(),
                    entity.getCoveredUntilSequence(),
                    entity.getSourceMemoryVersion(),
                    cursor.memoryUntilSequence(),
                    content,
                    entity.getPromptVersion(),
                    entity.getModelName()
            );
        } catch (SecurityException exception) {
            // 归属不一致表示安全不变量被破坏，必须 fail-closed，不能伪装成无摘要。
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            return failOpen(cursor, "INVALID_DATA", exception);
        }
    }

    private void validateMetadata(
            ChatHistoryCursor cursor,
            AgentConversationSummaryEntity entity
    ) {
        // 即使 Mapper 或测试替身返回异常数据，也不能把其他身份的数据带入请求。
        if (!Objects.equals(entity.getTenantId(), cursor.tenantId())
                || !Objects.equals(entity.getUserId(), cursor.userId())
                || !Objects.equals(
                        entity.getConversationId(), cursor.conversationId())) {
            throw new SecurityException("摘要归属与稳定历史游标不一致");
        }
        if (!Objects.equals(entity.getSchemaVersion(),
                properties.schemaVersion())) {
            throw new IllegalArgumentException("摘要结构版本不受支持");
        }
        if (entity.getCoveredUntilSequence() == null
                || entity.getCoveredUntilSequence()
                > cursor.memoryUntilSequence()) {
            throw new IllegalArgumentException("摘要覆盖边界超出稳定历史");
        }
        if (entity.getSourceMemoryVersion() == null
                || entity.getSourceMemoryVersion() > cursor.memoryVersion()) {
            throw new IllegalArgumentException("摘要来源版本超出稳定历史");
        }
    }

    private ChatSummarySnapshot failOpen(
            ChatHistoryCursor cursor,
            String reason,
            Exception exception
    ) {
        // 日志只记录安全元数据和异常类型，禁止输出实体、JSON 或异常消息。
        log.warn("summary_load_failed tenantId={}, userId={}, "
                        + "conversationId={}, memoryVersion={}, "
                        + "memoryUntilSequence={}, reason={}, exceptionType={}",
                cursor.tenantId(), cursor.userId(), cursor.conversationId(),
                cursor.memoryVersion(), cursor.memoryUntilSequence(), reason,
                exception.getClass().getSimpleName());
        return ChatSummarySnapshot.empty(cursor);
    }
}
