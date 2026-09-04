package com.xjjk.agent.chat.api.dto;

import java.util.List;

/**
 * 历史消息的游标分页响应。
 *
 * @param items 本页消息，按消息序号从小到大排列
 * @param nextBeforeSequence 加载更早消息使用的游标；
 *                           没有更多历史消息时为空
 * @param hasMore 是否还有更早的历史消息
 */
public record ChatMessagePageResponse(
        List<ChatMessageResponse> items,
        Long nextBeforeSequence,
        boolean hasMore
) {
}