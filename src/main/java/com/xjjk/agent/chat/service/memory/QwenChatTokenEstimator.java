package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * 纯文本、非思考模式下的完整聊天输入估算器。
 *
 * 使用固定 Qwen 资源对应的参考消息格式。
 * 仅支持系统提示词、普通历史问答和当前问题。
 * 不处理工具调用、多模态输入或独立的 reasoning_content。
 *
 * 构造的模板只用于本地估算，不能作为用户消息发给模型。
 * 结果不代表云端模型的精确计费用量。
 */
@Component
@RequiredArgsConstructor
public class QwenChatTokenEstimator {

    private final QwenTextTokenEstimator textEstimator;

    /** 消息开始标记。 */
    private static final String MESSAGE_START = "<|im_start|>";

    /** 消息结束标记。 */
    private static final String MESSAGE_END = "<|im_end|>\n";

    /** 当前参考模板在非思考模式下的助手生成前缀。 */
    private static final String GENERATION_PREFIX =
            "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    /**
     * 估算完整请求输入。
     *
     * @param systemPrompt 实际调用使用的系统提示词
     * @param history 按时间从旧到新排列的完整历史轮次
     * @param currentMessage 当前用户问题
     * @return 参考模板整体编码后的 Token 数
     */
    public long estimate(
            String systemPrompt,
            List<ChatHistoryTurn> history,
            String currentMessage
    ) {
        return estimate(systemPrompt, null, history, currentMessage);
    }

    /**
     * 估算包含低权限摘要消息的完整请求输入。
     *
     * 摘要按普通历史用户消息计算，不能拼入高权限系统提示词。
     */
    public long estimate(
            String systemPrompt,
            String summaryContext,
            List<ChatHistoryTurn> history,
            String currentMessage
    ) {
        Assert.hasText(systemPrompt, "系统提示词不能为空");
        Assert.hasText(currentMessage, "当前问题不能为空");
        Objects.requireNonNull(history, "历史轮次列表不能为 null");

        // 固定本次遍历使用的列表，并拒绝 null 元素。
        List<ChatHistoryTurn> turns = List.copyOf(history);

        StringBuilder prompt = new StringBuilder();

        appendMessage(prompt, "system", systemPrompt);

        if (summaryContext != null) {
            Assert.hasText(summaryContext, "摘要上下文不能为空白文本");
            appendMessage(prompt, "user", summaryContext);
        }

        long previousAssistantSequence = 0;

        for (ChatHistoryTurn turn : turns) {
            if (turn.userSequence() <= previousAssistantSequence) {
                throw new IllegalArgumentException(
                        "历史轮次必须按消息序号递增且不能重叠"
                );
            }

            appendMessage(prompt, "user", turn.userContent());
            appendMessage(prompt, "assistant", turn.assistantContent());

            previousAssistantSequence = turn.assistantSequence();
        }

        appendMessage(prompt, "user", currentMessage);
        prompt.append(GENERATION_PREFIX);

        // 对整个参考输入编码，不简单累加各段正文的 Token 数。
        return textEstimator.estimate(prompt.toString());
    }

    /**
     * 同时标识参考消息模板版本和底层分词策略版本。
     */
    public String strategyVersion() {
        return "qwen3-chatml-nothink-text-v1/"
                + textEstimator.strategyVersion();
    }

    /**
     * 保留正文原样，不 trim、不解析正文中的指令或标签。
     */
    private void appendMessage(
            StringBuilder target,
            String role,
            String content
    ) {
        target.append(MESSAGE_START)
                .append(role)
                .append('\n')
                .append(content)
                .append(MESSAGE_END);
    }
}
