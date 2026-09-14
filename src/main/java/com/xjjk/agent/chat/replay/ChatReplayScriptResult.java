package com.xjjk.agent.chat.replay;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 将 Lua 白名单返回码转换为稳定的 Java 领域结果。 */
public final class ChatReplayScriptResult {

    private ChatReplayScriptResult() {
    }

    public static long appendSequence(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw new ChatReplayUnavailableException("Redis 未返回聊天事件追加结果");
        }
        String code = text(result.getFirst());
        return switch (code) {
            case "OK" -> parseSequence(result);
            case "LIMIT" -> throw new ChatReplayLimitException();
            case "TERMINAL" -> throw new IllegalStateException("聊天流已经进入终态");
            case "NOT_FOUND", "IDENTITY_MISMATCH" ->
                    throw new ChatReplayUnavailableException("Redis 聊天任务不存在或归属不匹配");
            default -> throw new ChatReplayUnavailableException("Redis 返回未知追加结果");
        };
    }

    private static long parseSequence(List<?> result) {
        if (result.size() != 2) {
            throw new ChatReplayUnavailableException("Redis 返回的事件序号不完整");
        }
        try {
            long sequence = Long.parseLong(text(result.get(1)));
            if (sequence <= 0) throw new NumberFormatException("非正序号");
            return sequence;
        } catch (NumberFormatException exception) {
            throw new ChatReplayUnavailableException("Redis 返回的事件序号不合法", exception);
        }
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String text) {
            return text;
        }
        throw new ChatReplayUnavailableException("Redis Lua 返回类型不合法");
    }
}
