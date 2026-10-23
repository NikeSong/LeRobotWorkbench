package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * Opens the operating system's own folder picker. The workbench runs on the same machine as the browser.
 */
@Service
public class DirectoryChooserService {

    public Map<String, Object> choose() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return Map.of("ok", false, "message", "当前系统还没有接上自带的文件夹选择框。");
        }
        ProcessBuilder pb = new ProcessBuilder(
                "osascript",
                "-e",
                "POSIX path of (choose folder with prompt \"选择保存这些任务的文件夹\")");
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return Map.of("ok", false, "message", "文件夹选择超时。");
            }
            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                if (text.toLowerCase().contains("cancel") || text.contains("-128")) {
                    return Map.of("ok", false, "cancelled", true, "message", "已取消选择");
                }
                return Map.of("ok", false, "message", text.isBlank() ? "没有选中文件夹" : text);
            }
            if (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            Path path = Path.of(text).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                return Map.of("ok", false, "message", "选中的不是文件夹");
            }
            return Map.of("ok", true, "path", path.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "无法打开文件夹选择框: " + e.getMessage());
        }
    }
}
