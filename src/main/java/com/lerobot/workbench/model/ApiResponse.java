package com.lerobot.workbench.model;

import java.util.Map;

public record ApiResponse(boolean ok, String message, Map<String, Object> data) {

    public static ApiResponse success(String message, Map<String, Object> data) {
        return new ApiResponse(true, message, data);
    }

    public static ApiResponse success(Map<String, Object> data) {
        return new ApiResponse(true, "ok", data);
    }

    public static ApiResponse fail(String message) {
        return new ApiResponse(false, message, Map.of());
    }
}
