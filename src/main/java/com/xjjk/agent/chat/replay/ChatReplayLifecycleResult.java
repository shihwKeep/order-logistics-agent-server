package com.xjjk.agent.chat.replay;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 可恢复任务创建和取消 Lua 脚本的白名单结果解析器。 */
public final class ChatReplayLifecycleResult {

    private ChatReplayLifecycleResult() {
    }

    public static ChatReplayCreateResult created(List<?> result) {
        String code = first(result);
        return switch (code) {
            case "CREATED" -> ChatReplayCreateResult.CREATED;
            case "EXISTING" -> ChatReplayCreateResult.EXISTING;
            case "IDENTITY_MISMATCH" -> throw unavailable();
            default -> throw new ChatReplayUnavailableException("Redis 返回未知创建结果");
        };
    }

    public static boolean cancelled(List<?> result) {
        String code = first(result);
        return switch (code) {
            case "REQUESTED" -> true;
            case "TERMINAL" -> false;
            case "NOT_FOUND", "IDENTITY_MISMATCH" -> throw unavailable();
            default -> throw new ChatReplayUnavailableException("Redis 返回未知取消结果");
        };
    }

    private static String first(List<?> result) {
        if (result == null || result.size() != 1) {
            throw new ChatReplayUnavailableException("Redis 生命周期脚本返回不完整");
        }
        Object value = result.getFirst();
        if (value instanceof String text) return text;
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        throw new ChatReplayUnavailableException("Redis 生命周期脚本返回类型不合法");
    }

    private static ChatReplayUnavailableException unavailable() {
        return new ChatReplayUnavailableException("聊天任务不存在或不可访问");
    }
}
