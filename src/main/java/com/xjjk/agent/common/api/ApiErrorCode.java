package com.xjjk.agent.common.api;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "请求参数不合法"
    ),

    TENANT_HEADER_MISSING(
            HttpStatus.BAD_REQUEST,
            "TENANT_HEADER_MISSING",
            "缺少租户标识"
    ),

    TENANT_INVALID(
            HttpStatus.BAD_REQUEST,
            "TENANT_INVALID",
            "租户标识不合法"
    ),

    TENANT_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "TENANT_ACCESS_DENIED",
            "无权访问该租户"
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "系统繁忙，请稍后重试"
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ApiErrorCode(
            HttpStatus httpStatus,
            String code,
            String message
    ) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}