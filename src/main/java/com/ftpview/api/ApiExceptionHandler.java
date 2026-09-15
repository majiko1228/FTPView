package com.ftpview.api;

import com.ftpview.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    /** 保持前端错误响应契约一致。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handle(Exception error) {
        return ResponseEntity.badRequest()
                .body(
                        new ApiResponse<>(
                                400,
                                null,
                                error.getMessage() == null ? "操作失败" : error.getMessage()));
    }
}
