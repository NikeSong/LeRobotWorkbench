package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DatasetEditService {

    private final DatasetLibraryService datasets;
    private final ProcessCommandResolver commands;
    private final ObjectMapper mapper;

    public DatasetEditService(
            DatasetLibraryService datasets, ProcessCommandResolver commands, ObjectMapper mapper) {
        this.datasets = datasets;
        this.commands = commands;
        this.mapper = mapper;
    }

    public Map<String, Object> episodes(String repoId) {
        return run(repoId, "list", List.of());
    }

    public Map<String, Object> episode(String repoId, int episode) {
        return run(repoId, "episode", List.of("--episode", String.valueOf(episode)));
    }

    public Map<String, Object> trim(String repoId, int episode, int start, int end) {
        return run(
                repoId,
                "trim",
                List.of(
                        "--episode",
                        String.valueOf(episode),
                        "--start",
                        String.valueOf(start),
                        "--end",
                        String.valueOf(end)));
    }

    private Map<String, Object> run(String repoId, String command, List<String> extra) {
        Path root;
        try {
            root = datasets.require(repoId);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        Path script = commands.repoRoot().resolve("workbench/bridge/python/dataset_edit.py");
        List<String> cmd = new ArrayList<>(commands.pythonScript(script));
        cmd.add("--root");
        cmd.add(root.toString());
        cmd.add("--command");
        cmd.add(command);
        cmd.addAll(extra);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return Map.of("ok", false, "message", "处理超时");
            }
            String text = new String(out, StandardCharsets.UTF_8).trim();
            int jsonAt = text.lastIndexOf('\n');
            String payload = jsonAt >= 0 && !text.startsWith("{") ? text.substring(jsonAt + 1).trim() : text;
            if (process.exitValue() != 0 || !payload.startsWith("{")) {
                return Map.of("ok", false, "message", text.isEmpty() ? "处理失败" : text);
            }
            return mapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("ok", false, "message", "无法处理数据集: " + e.getMessage());
        }
    }
}
