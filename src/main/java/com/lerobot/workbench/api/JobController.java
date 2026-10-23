package com.lerobot.workbench.api;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/record/preview/{camera}")
    public ResponseEntity<byte[]> recordPreview(@PathVariable String camera) {
        byte[] jpeg = jobService.recordPreview(camera);
        if (jpeg == null || jpeg.length == 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control", "no-store").body(jpeg);
    }

    @PostMapping("/record/start")
    public ApiResponse recordStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startRecord(body));
    }

    @PostMapping("/record/stop")
    public ApiResponse recordStop() {
        return ApiResponse.success(jobService.stopRecord());
    }

    @PostMapping("/record/next")
    public ApiResponse recordNext() {
        return ApiResponse.success(jobService.nextRecord());
    }

    @GetMapping("/record/status")
    public ApiResponse recordStatus() {
        return ApiResponse.success(jobService.recordStatus());
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

    @GetMapping("/datasets/quality")
    public ApiResponse datasetQuality(@RequestParam String repoId) {
        return ApiResponse.success(jobService.qualityReport(repoId));
    }

    @GetMapping("/datasets/episodes")
    public ApiResponse datasetEpisodes(@RequestParam String repoId) {
        return ApiResponse.success(jobService.episodes(repoId));
    }

    @GetMapping("/datasets/episode")
    public ApiResponse datasetEpisode(@RequestParam String repoId, @RequestParam int episode) {
        return ApiResponse.success(jobService.episode(repoId, episode));
    }

    @PostMapping("/datasets/trim")
    public ApiResponse datasetTrim(@RequestBody Map<String, Object> body) {
        int episode = body.get("episode") instanceof Number number ? number.intValue() : 0;
        int start = body.get("start") instanceof Number number ? number.intValue() : 0;
        int end = body.get("end") instanceof Number number ? number.intValue() : 0;
        return ApiResponse.success(jobService.trimEpisode(String.valueOf(body.getOrDefault("repoId", "")), episode, start, end));
    }

    @GetMapping("/datasets/{repoId}")
    public ApiResponse datasetInfo(@PathVariable String repoId) {
        String id = repoId.contains("--") ? repoId.replace("--", "/") : repoId;
        return ApiResponse.success(jobService.datasetInfo(id));
    }

    @PostMapping("/datasets/save-all")
    public ApiResponse datasetSaveAll() {
        return ApiResponse.success(jobService.saveAllDatasets());
    }

    @PostMapping("/datasets/save")
    public ApiResponse datasetSave(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.saveDataset(String.valueOf(body.getOrDefault("repoId", ""))));
    }

    @PostMapping("/datasets/rename")
    public ApiResponse datasetRename(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.renameDataset(
                String.valueOf(body.getOrDefault("repoId", "")),
                String.valueOf(body.getOrDefault("displayName", ""))));
    }

    @PostMapping("/datasets/delete")
    public ApiResponse datasetDelete(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.deleteDataset(String.valueOf(body.getOrDefault("repoId", ""))));
    }

    @GetMapping("/datasets/media")
    public ResponseEntity<FileSystemResource> media(@RequestParam String repoId, @RequestParam String file) {
        Path path = jobService.datasetMedia(repoId, file);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).body(new FileSystemResource(path));
    }

    @PostMapping("/replay/start")
    public ApiResponse replayStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jobService.startReplay(body));
    }

    @PostMapping("/replay/stop")
    public ApiResponse replayStop() {
        return ApiResponse.success(jobService.stopReplay());
    }

    @GetMapping("/replay/status")
    public ApiResponse replayStatus() {
        return ApiResponse.success(jobService.replayStatus());
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
