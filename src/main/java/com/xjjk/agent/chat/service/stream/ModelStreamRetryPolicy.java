package com.xjjk.agent.chat.service.stream;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 模型流的有限重试判定器。
 *
 * <p>浏览器断点重连解决的是 SSE 消费端断开；这里处理的是上游模型流在
 * Agent 内部建立后被网络或供应商临时故障打断。重试次数、退避和错误分类
 * 独立于 SSE 重连，避免把一次问答误重跑成多次问答。</p>
 */
final class ModelStreamRetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double jitterRatio;

    ModelStreamRetryPolicy(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double jitterRatio
    ) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("模型流最大尝试次数必须大于零");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("模型流初始退避不能为负数");
        }
        if (maxBackoff == null || maxBackoff.isNegative()
                || initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("模型流最大退避配置无效");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio >= 1.0) {
            throw new IllegalArgumentException("模型流随机抖动比例必须在 [0, 1) 范围内");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.jitterRatio = jitterRatio;
    }

    boolean shouldRetry(Throwable failure, int attempt, boolean plainTextSent) {
        if (attempt >= maxAttempts || plainTextSent || failure == null) {
            return false;
        }
        return isTransientFailure(failure);
    }

    boolean isTransientFailure(Throwable failure) {
        return failure != null && isTransient(failure);
    }

    Duration backoff(int retryNumber) {
        if (retryNumber <= 0 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        int shift = Math.min(retryNumber - 1, 30);
        long baseMillis;
        try {
            baseMillis = Math.multiplyExact(initialBackoff.toMillis(), 1L << shift);
        } catch (ArithmeticException exception) {
            baseMillis = Long.MAX_VALUE;
        }
        long boundedMillis = Math.min(baseMillis, maxBackoff.toMillis());
        if (jitterRatio == 0.0 || boundedMillis == 0L) {
            return Duration.ofMillis(boundedMillis);
        }
        double factor = 1.0 + ThreadLocalRandom.current()
                .nextDouble(-jitterRatio, jitterRatio);
        long jittered = Math.max(1L, Math.round(boundedMillis * factor));
        return Duration.ofMillis(Math.min(jittered, maxBackoff.toMillis()));
    }

    private boolean isTransient(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException
                    || current instanceof IllegalArgumentException) {
                return false;
            }
            if (current instanceof WebClientResponseException web) {
                return isTransientStatus(web.getStatusCode().value());
            }
            if (current instanceof RestClientResponseException rest) {
                return isTransientStatus(rest.getStatusCode().value());
            }
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof IOException) {
                return true;
            }
            // Reactor/Netty 的部分连接异常在不同版本中不是稳定的公共类型，
            // 用类型名识别“连接提前关闭/连接重置”，避免绑定具体 Netty 版本。
            String typeName = current.getClass().getName();
            if (StringUtils.endsWithIgnoreCase(typeName, "PrematureCloseException")
                    || StringUtils.endsWithIgnoreCase(typeName, "ConnectionResetException")) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransientStatus(int status) {
        return status == 429 || status >= 500;
    }
}
