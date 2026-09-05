package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 会话历史缓存指标集中入口。
 */
@Component
public class ChatHistoryCacheMetrics {

    private final MeterRegistry registry;
    private final Counter hit;
    private final Counter miss;
    private final Counter error;
    private final Counter invalid;
    private final Counter writeSuccess;
    private final Counter writeError;
    private final Counter warmSuccess;
    private final Counter warmSkipped;
    private final Counter warmError;
    private final Counter warmRejected;
    private final Timer readDuration;
    private final Timer databaseLoadDuration;
    private final Timer warmDuration;

    public ChatHistoryCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.hit = registry.counter("chat.history.cache.hit");
        this.miss = registry.counter("chat.history.cache.miss");
        this.error = registry.counter("chat.history.cache.error");
        this.invalid = registry.counter("chat.history.cache.invalid");
        this.writeSuccess = registry.counter(
                "chat.history.cache.write.success"
        );
        this.writeError = registry.counter("chat.history.cache.write.error");
        this.warmSuccess = registry.counter(
                "chat.history.cache.warm.success"
        );
        this.warmSkipped = registry.counter(
                "chat.history.cache.warm.skipped"
        );
        this.warmError = registry.counter("chat.history.cache.warm.error");
        this.warmRejected = registry.counter(
                "chat.history.cache.warm.rejected"
        );
        this.readDuration = registry.timer(
                "chat.history.cache.read.duration"
        );
        this.databaseLoadDuration = registry.timer(
                "chat.history.cache.database.load.duration"
        );
        this.warmDuration = registry.timer(
                "chat.history.cache.warm.duration"
        );
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordReadDuration(Timer.Sample sample) {
        sample.stop(readDuration);
    }

    public void recordDatabaseLoadDuration(Timer.Sample sample) {
        sample.stop(databaseLoadDuration);
    }

    public void recordWarmDuration(Timer.Sample sample) {
        sample.stop(warmDuration);
    }

    public void hit() {
        hit.increment();
    }

    public void miss() {
        miss.increment();
    }

    public void error() {
        error.increment();
    }

    public void invalid() {
        invalid.increment();
    }

    public void writeSuccess() {
        writeSuccess.increment();
    }

    public void writeError() {
        writeError.increment();
    }

    public void warmSuccess() {
        warmSuccess.increment();
    }

    public void warmSkipped() {
        warmSkipped.increment();
    }

    public void warmError() {
        warmError.increment();
    }

    public void warmRejected() {
        warmRejected.increment();
    }
}
