package com.xjjk.agent.chat.routing;

/** 当前用户消息进入的业务查询执行路径。 */
public enum BusinessQueryMode {
    /** 普通对话，不要求本轮产生新的业务结构化结果。 */
    GENERAL,

    /** 已识别实时业务意图，但缺少必要公开标识，只返回确定性澄清问题。 */
    CLARIFICATION,

    /** 参数可由高置信规则唯一提取，绕过模型直接执行后端白名单动作。 */
    DIRECT,

    /** 需要模型选择工具或补全参数，但回答前必须验证本轮确实产生了业务结果。 */
    MODEL_REQUIRED,

    /** 同一问题需要两个或以上受控信息源，并由显式工作流编排。 */
    COMPOSITE
}
