package com.lerobot.workbench.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
public class DatasetQualityService {

    private final DatasetLibraryService datasets;
    private final ProcessCommandResolver commands;
    private final ObjectMapper mapper;

    public DatasetQualityService(
            DatasetLibraryService datasets, ProcessCommandResolver commands, ObjectMapper mapper) {
        this.datasets = datasets;
        this.commands = commands;
        this.mapper = mapper;
    }

    public Map<String, Object> evaluate(String repoId) {
        Path root;
        try {
            root = datasets.require(repoId);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        Path script = commands.repoRoot().resolve("workbench/bridge/python/dataset_quality.py");
        List<String> cmd = new ArrayList<>(commands.pythonLauncher());
        cmd.add(script.toString());
        cmd.add("--root");
        cmd.add(root.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        stdout.append(line);
                    }
                } catch (Exception ignored) {
                    // timeout path destroys the process
                }
            }, "dataset-quality");
            reader.setDaemon(true);
            reader.start();
            Thread err = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (in.readLine() != null) {
                        // keep the pipe from filling
                    }
                } catch (Exception ignored) {
                    // timeout path destroys the process
                }
            }, "dataset-quality-err");
            err.setDaemon(true);
            err.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Map.of("ok", false, "message", "质量评测超时");
            }
            reader.join(2000);
            String text = stdout.toString().trim();
            if (process.exitValue() != 0 || text.isEmpty() || !text.startsWith("{")) {
                return Map.of("ok", false, "message", text.isEmpty() ? "评测失败" : text);
            }
            Map<String, Object> report = mapper.readValue(text, new TypeReference<>() {});
            report.put("repoId", repoId);
            return report;
        } catch (Exception e) {
            return Map.of("ok", false, "message", "无法评测: " + e.getMessage());
        }
    }
}
