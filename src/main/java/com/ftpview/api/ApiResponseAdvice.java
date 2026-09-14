package com.ftpview.api;

import com.ftpview.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(basePackages = "com.ftpview.api")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
    /** 所有业务接口使用同一响应契约。 */
    @Override
    public boolean supports(
            MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    /** 成功响应包括无返回值的删除接口，错误响应不重复包装。 */
    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter method,
            MediaType type,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return body instanceof ApiResponse ? body : new ApiResponse<>(0, body, "操作成功");
    }
}
