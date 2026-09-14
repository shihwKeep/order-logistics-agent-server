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

    AUTH_HEADER_MISSING(
            HttpStatus.UNAUTHORIZED,
            "AUTH_HEADER_MISSING",
            "缺少登录凭证"
    ),

    AUTH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_TOKEN_INVALID",
            "登录状态无效或已过期"
    ),

    AUTH_CREDENTIALS_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_CREDENTIALS_INVALID",
            "账号或密码错误"
    ),

    AUTH_ACCOUNT_UNAVAILABLE(
            HttpStatus.FORBIDDEN,
            "AUTH_ACCOUNT_UNAVAILABLE",
            "当前账号不可登录，请联系管理员"
    ),

    AUTH_SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AUTH_SERVICE_UNAVAILABLE",
            "认证服务暂时不可用"
    ),

    AUTH_RESPONSE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "AUTH_RESPONSE_INVALID",
            "认证服务响应异常"
    ),

    CONVERSATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "CONVERSATION_NOT_FOUND",
            "会话不存在或无权访问"
    ),

    CONVERSATION_BUSY(
            HttpStatus.CONFLICT,
            "CONVERSATION_BUSY",
            "当前会话仍有请求未结束，请稍后重试"
    ),

    CHAT_REQUEST_INACTIVE(
            HttpStatus.CONFLICT,
            "CHAT_REQUEST_INACTIVE",
            "本次请求已结束或不再有效"
    ),

    CHAT_STREAM_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "CHAT_STREAM_NOT_FOUND",
            "聊天任务不存在、已过期或无权访问"
    ),

    CHAT_HISTORY_LOAD_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CHAT_HISTORY_LOAD_FAILED",
            "历史上下文加载失败，请稍后重试"
    ),

    CHAT_CONTEXT_TOO_LARGE(
            HttpStatus.BAD_REQUEST,
            "CHAT_CONTEXT_TOO_LARGE",
            "当前请求上下文超过预算，请缩短输入或减少附加内容"
    ),

    CHAT_ACTION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CHAT_ACTION_UNAVAILABLE",
            "当前物流查询暂时不可用，请稍后重试"
    ),

    MEMORY_WRITE_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MEMORY_WRITE_FAILED",
            "记忆保存失败，请稍后重试"
    ),

    MEMORY_DISABLED(
            HttpStatus.CONFLICT,
            "MEMORY_DISABLED",
            "记忆功能已关闭，可在“我的记忆”中开启"
    ),

    MEMORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "MEMORY_NOT_FOUND",
            "记忆不存在或无权访问"
    ),

    MEMORY_CONTENT_REJECTED(
            HttpStatus.BAD_REQUEST,
            "MEMORY_CONTENT_REJECTED",
            "这类内容不适合作为长期记忆保存"
    ),

    MEMORY_CLEAR_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MEMORY_CLEAR_FAILED",
            "记忆清理失败，请稍后重试"
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
