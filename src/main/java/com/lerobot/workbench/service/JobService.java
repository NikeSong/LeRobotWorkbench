package com.lerobot.workbench.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class JobService {

    private final RecordService recordService;
    private final DatasetLibraryService datasets;
    private final DatasetQualityService quality;
    private final DatasetEditService edits;
    private final ReplayService replayService;

    public JobService(
            RecordService recordService,
            DatasetLibraryService datasets,
            DatasetQualityService quality,
            DatasetEditService edits,
            ReplayService replayService) {
        this.recordService = recordService;
        this.datasets = datasets;
        this.quality = quality;
        this.edits = edits;
        this.replayService = replayService;
    }

    public Map<String, Object> startRecord(Map<String, Object> config) {
        return recordService.start(config);
    }

    public byte[] recordPreview(String camera) {
        return recordService.previewJpeg(camera);
    }

    public Map<String, Object> stopRecord() {
        return recordService.stop();
    }

    public Map<String, Object> nextRecord() {
        return recordService.nextEpisode();
    }

    public Map<String, Object> recordStatus() {
        return recordService.status();
    }

    public Map<String, Object> startTrain(Map<String, Object> config) {
        return unavailable("训练还没有接到本机的 lerobot-train，不会产生模型，也不会把这一步标成完成。");
    }

    public Map<String, Object> stopTrain() {
        return unavailable("没有正在运行的训练进程。");
    }

    public Map<String, Object> startEval(Map<String, Object> config) {
        return unavailable("仿真评估还没有接到 lerobot-eval。");
    }

    public Map<String, Object> startRollout(Map<String, Object> config) {
        return unavailable("真机 rollout 还没有接到机械臂和策略，不能启动。");
    }

    public Map<String, Object> listDatasets() {
        return datasets.list();
    }

    public Map<String, Object> qualityReport(String repoId) {
        return quality.evaluate(repoId);
    }

    public Map<String, Object> episodes(String repoId) {
        return edits.episodes(repoId);
    }

    public Map<String, Object> episode(String repoId, int episode) {
        return edits.episode(repoId, episode);
    }

    public Map<String, Object> trimEpisode(String repoId, int episode, int start, int end) {
        return edits.trim(repoId, episode, start, end);
    }

    public Map<String, Object> datasetInfo(String repoId) {
        try {
            return datasets.require(repoId) == null ? Map.of("ok", false) : datasets.list().get("datasets") instanceof List<?> rows
                    ? rows.stream()
                            .filter(row -> row instanceof Map<?, ?> map && repoId.equals(map.get("repoId")))
                            .findFirst()
                            .map(row -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = new java.util.LinkedHashMap<>((Map<String, Object>) row);
                                map.put("ok", true);
                                return map;
                            })
                            .orElse(Map.of("ok", false, "message", "找不到数据集"))
                    : Map.of("ok", false, "message", "找不到数据集");
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> saveAllDatasets() {
        return datasets.saveAll();
    }

    public Map<String, Object> saveDataset(String repoId) {
        try {
            Map<String, Object> out = datasets.save(repoId);
            out.put("ok", true);
            out.put("message", "已保存");
            return out;
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> renameDataset(String repoId, String displayName) {
        try {
            Map<String, Object> out = datasets.rename(repoId, displayName);
            if (Boolean.FALSE.equals(out.get("ok"))) {
                return out;
            }
            out.put("message", "已改名");
            return out;
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> deleteDataset(String repoId) {
        try {
            return datasets.delete(repoId);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public java.nio.file.Path datasetMedia(String repoId, String file) {
        return datasets.media(repoId, file);
    }

    public Map<String, Object> startReplay(Map<String, Object> body) {
        return replayService.start(body);
    }

    public Map<String, Object> stopReplay() {
        return replayService.stop();
    }

    public Map<String, Object> replayStatus() {
        return replayService.status();
    }

    public Map<String, Object> runAlign(Map<String, Object> config) {
        return unavailable("数据对齐还没有实现，不会改数据集，也不会解锁后面的步骤。");
    }

    public Map<String, Object> runAnnotate(Map<String, Object> config) {
        return unavailable("智能标注还没有实现，不会改数据集。");
    }

    private static Map<String, Object> unavailable(String message) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", false);
        out.put("implemented", false);
        out.put("message", message);
        return out;
    }
}
