package com.xjjk.agent.chat.replay;

import com.xjjk.agent.chat.config.ChatStreamProperties;

import java.util.Objects;
import java.util.regex.Pattern;

/** 为同一请求生成位于同一 Redis Cluster slot 的补发 Key。 */
public final class ChatReplayKeyFactory {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final String prefix;

    public ChatReplayKeyFactory(ChatStreamProperties.Replay properties) {
        this.prefix = Objects.requireNonNull(properties, "聊天流补发配置不能为空")
                .keyPrefix();
    }

    public String meta(long tenantId, long userId, String requestId) {
        return base(tenantId, userId, requestId) + ":meta";
    }

    public String events(long tenantId, long userId, String requestId) {
        return base(tenantId, userId, requestId) + ":events";
    }

    public String control(long tenantId, long userId, String requestId) {
        return base(tenantId, userId, requestId) + ":control";
    }

    private String base(long tenantId, long userId, String requestId) {
        if (tenantId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("租户和用户 ID 必须大于零");
        }
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("聊天请求 ID 不合法");
        }
        return prefix + ":{" + tenantId + ":" + userId + ":" + requestId + "}";
    }
}
