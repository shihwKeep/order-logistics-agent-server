package com.xjjk.agent.memory.answer;

import java.util.Objects;

/** 确定性记忆问答结果；只有 NOT_HANDLED 会继续进入通用模型链路。 */
public record DeterministicUserMemoryAnswerResult(
        Outcome outcome,
        String assistantText) {

    public DeterministicUserMemoryAnswerResult {
        Objects.requireNonNull(outcome, "直答结果不能为空");
        if (outcome == Outcome.NOT_HANDLED && assistantText != null) {
            throw new IllegalArgumentException("未处理结果不能携带回答");
        }
        if (outcome != Outcome.NOT_HANDLED
                && (assistantText == null || assistantText.isBlank())) {
            throw new IllegalArgumentException("已处理直答必须包含回答");
        }
    }

    public boolean handled() {
        return outcome != Outcome.NOT_HANDLED;
    }

    public static DeterministicUserMemoryAnswerResult notHandled() {
        return new DeterministicUserMemoryAnswerResult(Outcome.NOT_HANDLED, null);
    }

    public enum Outcome {
        NOT_HANDLED,
        ANSWERED,
        UNAVAILABLE
    }
}
