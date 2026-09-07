package com.xjjk.agent.chat.action;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;

/** 第一版允许前端卡片直接触发的动作白名单。 */
public enum ChatActionType {

    /** 使用完整订单号查询该订单的实时物流。 */
    QUERY_ORDER_LOGISTICS;

    /** 未知值严格失败，不允许降级为模型自由解释。 */
    public static ChatActionType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        try {
            return valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
    }
}
