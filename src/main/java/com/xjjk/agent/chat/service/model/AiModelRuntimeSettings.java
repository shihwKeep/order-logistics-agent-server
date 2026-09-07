package com.xjjk.agent.chat.service.model;

import com.xjjk.agent.chat.config.AiModelCapabilityProperties;
import com.xjjk.agent.chat.domain.memory.ChatContextBudget;
import com.xjjk.agent.chat.service.memory.ChatContextBudgetCalculator;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 当前模型的运行配置快照。
 *
 * 启动时读取并校验配置，供模型调用和预算计算共同使用。
 * 当前仅适配纯文本、非思考模式的调用。
 * 不保存 API Key，不向外暴露内部可变选项。
 */
@Component
public class AiModelRuntimeSettings {

    private final AiModelCapabilityProperties capability;

    private final ChatContextBudgetCalculator budgetCalculator;

    private final OpenAiChatOptions optionsSnapshot;

    public AiModelRuntimeSettings(
            OpenAiChatProperties chatProperties,
            AiModelCapabilityProperties capability,
            ChatContextBudgetCalculator budgetCalculator
    ) {
        this.capability = capability;
        this.budgetCalculator = budgetCalculator;

        OpenAiChatOptions configured = chatProperties.getOptions();

        if (!capability.model().equals(configured.getModel())) {
            throw new IllegalStateException(
                    "模型能力配置中的名称与实际调用模型不一致"
            );
        }

        if (configured.getMaxCompletionTokens() != null) {
            throw new IllegalStateException(
                    "当前适配器使用 max-tokens，不能同时配置 max-completion-tokens"
            );
        }

        Integer maxTokens = configured.getMaxTokens();

        if (maxTokens == null || maxTokens <= 0) {
            throw new IllegalStateException(
                    "必须明确配置大于 0 的模型输出上限 max-tokens"
            );
        }

        if (maxTokens > capability.maxOutputTokens()
                || maxTokens >= capability.contextTokens()) {
            throw new IllegalStateException(
                    "当前请求输出上限超过模型能力或没有为输入预留空间"
            );
        }

        /*
         * 当前由适配器统一指定非思考模式。
         * 暂不接受另外配置 reasoning-effort 或 extra-body，
         * 防止额外参数改变模式，或者覆盖已经校验的请求字段。
         * 后续扩展时应按允许的参数逐项适配。
         */
        if (configured.getReasoningEffort() != null
                || (configured.getExtraBody() != null
                && !configured.getExtraBody().isEmpty())) {
            throw new IllegalStateException(
                    "当前非思考模式适配器不接受 reasoning-effort 或 extra-body 配置"
            );
        }

        // 复制框架选项，不直接修改框架绑定的配置对象。
        this.optionsSnapshot = OpenAiChatOptions.fromOptions(configured);

        // 使用真正的 Boolean，明确关闭百炼思考模式。
        this.optionsSnapshot.setExtraBody(
                Map.of("enable_thinking", false)
        );

        // 提前发现模型限制与应用预算冲突，避免首次聊天时才报错。
        calculateBudget(0);
    }

    /**
     * 返回供 ChatClient 使用的选项副本。
     * 调用方修改副本，不会改变本适配器中的模型和输出上限。
     */
    public OpenAiChatOptions createChatOptions() {
        return OpenAiChatOptions.fromOptions(optionsSnapshot);
    }

    /**
     * 使用与模型调用相同的输出上限计算预算。
     *
     * @param fixedEstimatedTokens 历史消息以外输入的 Token 估算量
     */
    public ChatContextBudget calculateBudget(long fixedEstimatedTokens) {
        return budgetCalculator.calculate(
                capability.contextTokens(),
                capability.maxInputTokens(),
                optionsSnapshot.getMaxTokens(),
                fixedEstimatedTokens
        );
    }
}
