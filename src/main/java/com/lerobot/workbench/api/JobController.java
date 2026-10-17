package com.lerobot.workbench.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.service.JobService;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/record/start")
    public ApiResponse recordStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startRecord(body));
    }

    @PostMapping("/record/stop")
    public ApiResponse recordStop() {
        return ApiResponse.success(jobService.stopRecord());
    }

    @PostMapping("/train/start")
    public ApiResponse trainStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startTrain(body));
    }

    @PostMapping("/train/stop")
    public ApiResponse trainStop() {
        return ApiResponse.success(jobService.stopTrain());
    }

    @PostMapping("/eval/start")
    public ApiResponse evalStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startEval(body));
    }

    @PostMapping("/rollout/start")
    public ApiResponse rolloutStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startRollout(body));
    }

    @GetMapping("/datasets")
    public ApiResponse datasets() {
        return ApiResponse.success(jobService.listDatasets());
    }

    @GetMapping("/datasets/{repoId}")
    public ApiResponse datasetInfo(@PathVariable String repoId) {
        // allow owner--name style path by replacing --
        String id = repoId.contains("--") ? repoId.replace("--", "/") : repoId;
        return ApiResponse.success(jobService.datasetInfo(id));
    }

    @PostMapping("/align/run")
    public ApiResponse align(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.runAlign(body));
    }

    @PostMapping("/annotate/run")
    public ApiResponse annotate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.runAnnotate(body));
    }
}
