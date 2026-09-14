package com.xjjk.agent.chat.stream;

import com.xjjk.agent.chat.domain.MessageStatus;

import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

/**
 * 单个 SSE 请求的执行控制对象。
 *
 * 每个请求单独创建，不能在多个请求之间共享。
 * 协调回调线程的取消操作与工作线程的数据库收尾。
 */
public final class ChatStreamControl {

    private final BooleanSupplier externalCancellation;

    /** 本次请求对应的异步任务。 */
    private FutureTask<?> task;

    /** 首个停止原因；为空表示尚未收到停止请求。 */
    private MessageStatus stopReason;

    /** 是否已经进入收尾阶段。 */
    private boolean finalizing;

    public ChatStreamControl() {
        this(() -> false);
    }

    /**
     * @param externalCancellation 跨实例停止标志探针；可恢复模式下读取 Redis control
     */
    public ChatStreamControl(BooleanSupplier externalCancellation) {
        this.externalCancellation = Objects.requireNonNull(
                externalCancellation, "外部取消探针不能为空");
    }

    /**
     * 绑定异步任务，只允许绑定一次。
     * 应在提交线程池之前完成绑定。
     */
    public synchronized void bind(FutureTask<?> task) {
        Objects.requireNonNull(task, "异步任务不能为空");

        if (this.task != null || finalizing) {
            throw new IllegalStateException("当前状态不允许绑定任务");
        }

        this.task = task;

        // 兼容停止信号先于任务绑定到达的情况。
        if (stopReason != null) {
            task.cancel(true);
        }
    }

    /**
     * 请求停止生成，保留第一个停止原因。
     *
     * 进入收尾后，不再通过本对象中断工作线程，
     * 避免取消回调打断数据库收尾事务。
     *
     * @return 是否接受了本次停止请求，不代表任务已经停止
     */
    public synchronized boolean requestStop(MessageStatus reason) {
        if (reason != MessageStatus.TIMEOUT
                && reason != MessageStatus.CANCELLED
                && reason != MessageStatus.OUTPUT_ERROR) {
            throw new IllegalArgumentException("不支持的停止原因");
        }

        if (finalizing || stopReason != null) {
            return false;
        }

        stopReason = reason;

        if (task != null) {
            task.cancel(true);
        }

        return true;
    }

    /** 由工作线程检查是否应停止准备工作或读取模型流。 */
    public boolean isStopRequested() {
        synchronized (this) {
            if (stopReason != null || Thread.currentThread().isInterrupted()) {
                return true;
            }
        }

        // Redis 查询不能持有对象锁，避免网络抖动阻塞取消回调和收尾线程。
        if (externalCancellation.getAsBoolean()) {
            requestStop(MessageStatus.CANCELLED);
        }

        synchronized (this) {
            return stopReason != null || Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 由工作线程在 finally 中进入收尾阶段，只调用一次。
     *
     * @return 已记录的停止原因；
     *         null 表示没有停止信号，沿用模型处理得到的结果状态
     */
    public synchronized MessageStatus beginFinalization() {
        if (finalizing) {
            throw new IllegalStateException("不能重复进入收尾阶段");
        }

        // 兼容应用关闭等外部线程中断。
        if (stopReason == null && Thread.currentThread().isInterrupted()) {
            stopReason = MessageStatus.CANCELLED;
        }

        finalizing = true;
        return stopReason;
    }
}
