package com.adarosatas.vocabtrim.common.api;
//数据形状：HTTP响应DTO
/*
{
  "code": "SYNC_CONFLICT",
  "message": "云端数据已经被...",
  "timestamp": "2026-09-11T..."
}
 */
import java.time.Instant;

public record ApiError(
        String code,
        String message,
        Instant timestamp
) {
    //简化以后不用写写new
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
