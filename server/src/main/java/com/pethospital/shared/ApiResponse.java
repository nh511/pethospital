package com.pethospital.shared;
public record ApiResponse<T>(String code, String message, T data, String traceId) {
 public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("OK", "成功", data, TraceFilter.current()); }
 public static ApiResponse<Object> error(String code,String message,Object data) { return new ApiResponse<>(code,message,data,TraceFilter.current()); }
}

