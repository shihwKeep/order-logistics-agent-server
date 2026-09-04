package com.xjjk.agent.chat.domain;

/**
 * 持久化消息的处理状态。
 *
 * 非成功状态可能仍有部分正文，
 * 不能仅凭正文非空就认为回答完整。
 */
public enum MessageStatus {

    /** 助手正在生成回答，尚未进入最终状态。 */
    GENERATING,

    /** 用户消息已保存，或助手回答已正常完成并保存。 */
    SUCCESS,

    /** 模型调用或生成过程失败。 */
    FAILED,

    /** 达到模型输出长度上限，回答可能不完整。 */
    OUTPUT_LIMIT,

    /** 本次请求超时。 */
    TIMEOUT,

    /** 本次任务已取消，不代表模型供应商一定停止计费。 */
    CANCELLED,

    /** 向客户端发送数据失败，不能仅凭此状态确定断开原因。 */
    OUTPUT_ERROR,

    /** 模型未返回有效的文本内容。 */
    EMPTY_RESPONSE,

    /** 模型未正常结束，且不属于已识别的长度上限情况。 */
    INCOMPLETE,

    /** 请求占用过期且未正常收尾，由后续恢复逻辑标记。 */
    INTERRUPTED
}
