package com.xjjk.agent.common.api;

import java.time.Instant;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        Instant timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "SUCCESS",
                "success",
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> failure(ApiErrorCode errorCode) {
        return new ApiResponse<>(
                errorCode.code(),
                errorCode.message(),
                null,
                Instant.now()
        );
    }
}
