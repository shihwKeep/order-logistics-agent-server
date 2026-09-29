package com.xjjk.agent.chat.stream;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地流式评测用的增量发送节流器。
 *
 * <p>默认延迟为零，不改变生产行为；配置正数时仅在 delta 事件前等待，
 * 便于观察浏览器断线、重连和逐步输出。</p>
 */
public final class ChatStreamEventDelay {

    private static final int TEST_CHUNK_CODE_POINTS = 16;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final long delayMillis;
    private final Sleeper sleeper;

    public ChatStreamEventDelay(long delayMillis) {
        this(delayMillis, Thread::sleep);
    }

    public ChatStreamEventDelay(long delayMillis, Sleeper sleeper) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("聊天流测试延迟不能为负数");
        }
        this.delayMillis = delayMillis;
        this.sleeper = Objects.requireNonNull(sleeper, "延迟执行器不能为空");
    }

    public void beforeDelta() {
        if (delayMillis == 0) {
            return;
        }
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 测试延迟开启时，将上游可能一次返回的整段文本切成较小的增量事件。
     * 默认零延迟返回原文本，避免改变正常 SSE 事件形状。
     */
    public List<String> chunks(String text) {
        if (delayMillis == 0 || text == null || text.isEmpty()) {
            return Collections.singletonList(text);
        }
        int[] codePoints = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>(
                (codePoints.length + TEST_CHUNK_CODE_POINTS - 1)
                        / TEST_CHUNK_CODE_POINTS);
        for (int offset = 0; offset < codePoints.length; offset += TEST_CHUNK_CODE_POINTS) {
            int end = Math.min(offset + TEST_CHUNK_CODE_POINTS, codePoints.length);
            chunks.add(new String(codePoints, offset, end - offset));
        }
        return chunks;
    }
}
