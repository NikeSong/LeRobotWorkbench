package com.lerobot.workbench.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.service.DatasetLibraryService;
import com.lerobot.workbench.service.DirectoryChooserService;
import com.lerobot.workbench.service.SystemService;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemService systemService;
    private final DatasetLibraryService datasets;
    private final DirectoryChooserService chooser;

    public SystemController(
            SystemService systemService, DatasetLibraryService datasets, DirectoryChooserService chooser) {
        this.systemService = systemService;
        this.datasets = datasets;
        this.chooser = chooser;
    }

    @GetMapping("/dataset-home")
    public ApiResponse datasetHome() {
        Path local = datasets.dataHome().resolve("local");
        return ApiResponse.success(Map.of(
                "path", local.toString(),
                "exists", Files.isDirectory(local),
                "placeholderExists", Files.isDirectory(datasets.dataHome().resolve("local/my_pick_task"))));
    }

    @PostMapping("/choose-directory")
    public ApiResponse chooseDirectory() {
        return ApiResponse.success(chooser.choose());
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
