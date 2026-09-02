package com.xjjk.agent.common.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        OffsetDateTime timestamp
) {

    private static final ZoneId DEFAULT_ZONE_ID =
            ZoneId.of("Asia/Shanghai");

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "SUCCESS",
                "success",
                data,
                OffsetDateTime.now(DEFAULT_ZONE_ID)
        );
    }

    public static <T> ApiResponse<T> failure(ApiErrorCode errorCode) {
        return new ApiResponse<>(
                errorCode.code(),
                errorCode.message(),
                null,
                OffsetDateTime.now(DEFAULT_ZONE_ID)
        );
    }
}
