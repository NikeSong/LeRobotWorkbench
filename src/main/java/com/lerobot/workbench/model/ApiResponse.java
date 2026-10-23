package com.lerobot.workbench.model;

import java.util.Map;

public record ApiResponse(boolean ok, String message, Map<String, Object> data) {

    public static ApiResponse success(String message, Map<String, Object> data) {
        return new ApiResponse(true, message, data);
    }

    public static ApiResponse success(Map<String, Object> data) {
        boolean ok = data == null || !Boolean.FALSE.equals(data.get("ok"));
        String message = "ok";
        if (data != null && data.get("message") != null) {
            message = String.valueOf(data.get("message"));
        } else if (!ok) {
            message = "失败";
        }
        return new ApiResponse(ok, message, data == null ? Map.of() : data);
    }

    public static ApiResponse fail(String message) {
        return new ApiResponse(false, message, Map.of());
    }
}
