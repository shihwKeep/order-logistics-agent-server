package com.xjjk.agent.common.exception;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception
    ) {
        ApiErrorCode errorCode = exception.errorCode();

        return ResponseEntity
                .status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode));
    }
}
