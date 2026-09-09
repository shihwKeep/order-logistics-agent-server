package com.xjjk.agent.tool;

/**
 * 把工具产生的完整结构化结果发布到当前请求的输出通道。
 *
 * <p>具体实现由当前聊天轮次注入：先把结果转换成待落库快照，再根据执行模式
 * 立即发布或暂存。业务工具不直接依赖 SSE Session，也不接触数据库实体。</p>
 */
@FunctionalInterface
public interface ToolOutputPublisher {
    /** 发布一次已经从下游取得的完整结构化结果。 */
    void publish(ToolUiResult result);
}
