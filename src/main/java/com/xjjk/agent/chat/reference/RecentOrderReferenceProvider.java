package com.xjjk.agent.chat.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** 从当前会话最近的结构化结果中推导唯一订单指代，不保存独立可变状态。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecentOrderReferenceProvider {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final AgentMessageResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    public Optional<RecentOrderReference> findUniqueBefore(
            AgentIdentity identity,
            String conversationId,
            long beforeSequence) {
        if (identity == null) {
            throw new IllegalArgumentException("认证身份不能为空");
        }
        return findUniqueBefore(
                identity.tenantId(), identity.userId(), conversationId, beforeSequence);
    }

    /** 使用已经由后端确认的本轮归属范围查询引用。 */
    public Optional<RecentOrderReference> findUniqueBefore(
            ChatTurnContext turn,
            long beforeSequence) {
        if (turn == null) {
            throw new IllegalArgumentException("对话上下文不能为空");
        }
        return findUniqueBefore(
                turn.tenantId(), turn.userId(), turn.conversationId(), beforeSequence);
    }

    private Optional<RecentOrderReference> findUniqueBefore(
            long tenantId,
            long userId,
            String conversationId,
            long beforeSequence) {
        if (tenantId <= 0 || userId <= 0 || conversationId == null
                || conversationId.isBlank() || beforeSequence <= 0) {
            return Optional.empty();
        }
        AgentMessageResultEntity latest = resultMapper.selectLatestBefore(
                tenantId, userId, conversationId, beforeSequence);
        if (latest == null || latest.getSchemaVersion() == null
                || latest.getSchemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(latest.getPayloadJson());
            if ("order-list".equals(latest.getKind())) {
                return fromOrderList(payload);
            }
            if ("logistics-timeline".equals(latest.getKind())) {
                return fromLogistics(payload);
            }
            return Optional.empty();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            // 结构化快照损坏时不向前寻找旧结果，也不在日志打印可能含业务数据的 JSON。
            log.warn("recent_order_reference_skipped conversationId={}, kind={}, schemaVersion={}, exceptionType={}",
                    conversationId, latest.getKind(), latest.getSchemaVersion(),
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<RecentOrderReference> fromOrderList(JsonNode payload) {
        if (payload == null || !payload.path("items").isArray()) {
            return Optional.empty();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode item : payload.path("items")) {
            String orderCode = text(item.path("orderCode"));
            if (orderCode != null) {
                codes.add(orderCode);
            }
        }
        return codes.size() == 1
                ? safe(codes.iterator().next())
                : Optional.empty();
    }

    private Optional<RecentOrderReference> fromLogistics(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        return safe(text(payload.path("order").path("orderCode")));
    }

    private Optional<RecentOrderReference> safe(String orderCode) {
        if (orderCode == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RecentOrderReference(orderCode));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }
}
