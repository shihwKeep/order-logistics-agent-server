package com.xjjk.agent.memory.answer;

import java.util.Objects;

/** 确定性记忆问答结果；只有 NOT_HANDLED 会继续进入通用模型链路。 */
public record DeterministicUserMemoryAnswerResult(
        Outcome outcome,
        String assistantText) {

    public DeterministicUserMemoryAnswerResult {
        // NOT_HANDLED 表示释放给后续 Agent，不能携带半成品正文；其余终态必须有安全回答。
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
        // ChatTurnRunner 只依据该方法决定是否结束意图责任链。
        return outcome != Outcome.NOT_HANDLED;
    }

    public static DeterministicUserMemoryAnswerResult notHandled() {
        return new DeterministicUserMemoryAnswerResult(Outcome.NOT_HANDLED, null);
    }

    public enum Outcome {
        /** 当前模块不接管，继续进入普通模型链路。 */
        NOT_HANDLED,
        /** 已从权威记忆事实生成确定性回答。 */
        ANSWERED,
        /** 已识别为记忆问题，但事实源终审服务暂时不可用。 */
        UNAVAILABLE
    }
}
