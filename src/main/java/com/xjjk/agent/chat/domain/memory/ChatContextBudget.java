package com.xjjk.agent.chat.domain.memory;

/**
 * 当前请求的上下文预算分配结果。
 *
 * 只保存预算数字，不保存问题、历史正文或敏感凭据。
 * 这是历史消息筛选前的预算，不代表模型实际消耗的 Token 数。
 *
 * @param usableInputTokens 综合各项限制并扣除安全余量后的可用输入预算
 * @param fixedEstimatedTokens 历史消息以外的输入估算量，包含系统提示词、
 *                            当前问题以及适用的消息格式等开销
 * @param historyBudgetTokens 剩余可分配给历史消息的预算
 */
public record ChatContextBudget(
        long usableInputTokens,
        long fixedEstimatedTokens,
        long historyBudgetTokens
) {

    public ChatContextBudget {
        if (usableInputTokens <= 0) {
            throw new IllegalArgumentException("可用输入预算必须大于 0");
        }

        if (fixedEstimatedTokens < 0 || historyBudgetTokens < 0) {
            throw new IllegalArgumentException("Token 预算不能为负数");
        }

        if (fixedEstimatedTokens > usableInputTokens) {
            throw new IllegalArgumentException("固定输入已超过可用预算");
        }

        if (historyBudgetTokens
                != usableInputTokens - fixedEstimatedTokens) {
            throw new IllegalArgumentException("历史预算与剩余输入预算不一致");
        }
    }
}
