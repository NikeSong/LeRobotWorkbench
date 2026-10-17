package com.lerobot.workbench.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.service.SystemService;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/overview")
    public ApiResponse overview() {
        return ApiResponse.success(systemService.overview());
    }

    @GetMapping("/modules")
    public ApiResponse modules() {
        return ApiResponse.success(Map.of("modules", systemService.modules()));
    }
}
