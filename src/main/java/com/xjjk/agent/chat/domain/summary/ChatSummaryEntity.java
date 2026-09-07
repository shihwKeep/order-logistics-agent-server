package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

/**
 * 摘要中需要后续对话继续识别的重要实体。
 *
 * @param entityType 受控实体类别
 * @param displayValue 脱敏后的展示值
 * @param sourceType 实体来源消息角色
 * @param sourceSequence 来源消息序号
 */
public record ChatSummaryEntity(
        String entityType,
        String displayValue,
        ChatSummarySourceType sourceType,
        long sourceSequence
) {

    public ChatSummaryEntity {
        if (!StringUtils.hasText(entityType)
                || !StringUtils.hasText(displayValue)) {
            throw new IllegalArgumentException("摘要实体类型和值不能为空");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("摘要实体来源不能为空");
        }
        if (sourceSequence < 1) {
            throw new IllegalArgumentException("摘要实体来源序号必须大于零");
        }
    }

    /** 实体类型和值都可能包含业务数据，因此只输出来源元数据。 */
    @Override
    public String toString() {
        return "ChatSummaryEntity[sourceType=" + sourceType
                + ", sourceSequence=" + sourceSequence
                + ", content=<redacted>]";
    }
}
