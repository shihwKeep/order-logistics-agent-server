package com.xjjk.agent.common.exception;

import com.xjjk.agent.common.api.ApiErrorCode;

public class BusinessException extends RuntimeException {

    private final ApiErrorCode errorCode;

    public BusinessException(ApiErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }
}
