package com.ftpview.dto;

public class ApiResponse<T> {
    public final int code;
    public final T data;
    public final String msg;

    /** 统一接口响应，code 为 0 表示成功，失败保留 HTTP 错误状态。 */
    public ApiResponse(int code, T data, String msg) {
        this.code = code;
        this.data = data;
        this.msg = msg;
    }
}
