package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.config.ChatContextProperties;
import com.xjjk.agent.chat.domain.memory.ChatContextBudget;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 当前请求的上下文预算计算器。
 *
 * 仅进行预算分配，不读取历史、不执行分词、不调用模型。
 * 模型限制及输出预留必须来自当前请求实际使用的模型配置。
 *
 * 可用输入预算 =
 * min(
 *     应用输入上限,
 *     模型输入上限,
 *     模型上下文上限 - 实际输出预留
 * )
 * - 安全余量
 * - 工具定义及工具调用协议预留
 */
@Component
@RequiredArgsConstructor
public class ChatContextBudgetCalculator {

    private final ChatContextProperties properties;

    /**
     * 计算历史消息可使用的预算。
     *
     * @param modelContextTokens 模型上下文上限
     * @param modelMaxInputTokens 模型单次输入上限
     * @param reservedOutputTokens 当前请求实际采用的输出上限；
     *                            调用方需事先确认其符合模型输出限制
     * @param fixedEstimatedTokens 历史以外输入的估算量，
     *                             包括系统提示词、当前问题及相关格式开销
     * @return 当前请求的预算分配结果
     */
    public ChatContextBudget calculate(
            long modelContextTokens,
            long modelMaxInputTokens,
            long reservedOutputTokens,
            long fixedEstimatedTokens
    ) {
        if (modelContextTokens <= 0 || modelMaxInputTokens <= 0) {
            throw new IllegalArgumentException("模型上下文和输入上限必须大于 0");
        }

        if (reservedOutputTokens <= 0
                || reservedOutputTokens >= modelContextTokens) {
            throw new IllegalArgumentException("模型输出预留不合法");
        }

        if (fixedEstimatedTokens < 0) {
            throw new IllegalArgumentException("固定输入估算量不能为负数");
        }

        // 输入不能占用为模型回答预留的空间。
        // 输入上下文上限 = 模型上下文上限 - 当前请求实际模型输出文上限
        long contextInputLimit =
                modelContextTokens - reservedOutputTokens;

        // 同时满足应用策略、模型输入限制和上下文容量限制。
        long effectiveInputLimit = Math.min(
                properties.maxInputTokens(),
                Math.min(modelMaxInputTokens, contextInputLimit)
        );

        // 工具 schema 和工具调用协议同样占用模型输入，但文本分词器无法直接估算；
        // 因此在统一入口与估算安全余量分开配置、一次性扣除。
        long usableInputTokens =
                effectiveInputLimit
                        - properties.safetyMarginTokens()
                        - properties.toolReserveTokens();

        if (usableInputTokens <= 0) {
            // 即使没有用户内容也无法分配预算，属于配置问题。
            throw new IllegalStateException(
                    "模型限制与安全余量配置导致无可用输入预算"
            );
        }

        if (fixedEstimatedTokens > usableInputTokens) {
            // 不截断当前问题，也不继续发起注定超出应用预算的请求。
            throw new BusinessException(
                    ApiErrorCode.CHAT_CONTEXT_TOO_LARGE
            );
        }

        return new ChatContextBudget(
                usableInputTokens,
                fixedEstimatedTokens,
                usableInputTokens - fixedEstimatedTokens
        );
    }
}
