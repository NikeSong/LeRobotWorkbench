package com.lerobot.workbench.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

/**
 * Discovers serial ports on the local machine and explains them in plain language.
 * Users pick a device card or run the unplug wizard — they never type a path.
 */
@Service
public class PortDiscoveryService {

    private static final Set<String> PREFIXES = Set.of(
            "tty.usbmodem",
            "tty.usbserial",
            "cu.usbmodem",
            "cu.usbserial",
            "ttyACM",
            "ttyUSB",
            "tty.wchusbserial",
            "cu.wchusbserial");

    /** Last snapshot used by the unplug wizard. */
    private volatile List<String> baseline = List.of();

    public List<Map<String, Object>> listPorts() {
        List<Map<String, Object>> ports = new ArrayList<>();
        Path dev = Path.of("/dev");
        if (!Files.isDirectory(dev)) {
            return ports;
        }
        try (Stream<Path> stream = Files.list(dev)) {
            stream.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(this::isCandidate)
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> ports.add(describe(name)));
        } catch (IOException ignored) {
            return ports;
        }
        // Prefer cu.* on macOS (call-out devices); hide the matching tty.* twin.
        return dedupeMacTwins(ports);
    }

    public Map<String, Object> beginIdentify() {
        baseline = listPorts().stream().map(p -> String.valueOf(p.get("path"))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", baseline.size());
        out.put("ports", baseline);
        out.put("hint", baseline.isEmpty()
                ? "还没发现串口。请先插上机械臂 USB，再点「开始识别」。"
                : "请拔掉你要识别的那条机械臂 USB，然后点「我已拔出」。");
        return out;
    }

    public Map<String, Object> finishIdentify() {
        List<String> now = listPorts().stream().map(p -> String.valueOf(p.get("path"))).toList();
        List<String> removed = baseline.stream().filter(p -> !now.contains(p)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", baseline);
        out.put("after", now);
        if (removed.size() == 1) {
            String path = removed.get(0);
            Map<String, Object> port = describe(Path.of(path).getFileName().toString());
            out.put("ok", true);
            out.put("port", port);
            out.put("waitingPath", path);
            // Next reconnect check compares against the unplugged snapshot.
            baseline = now;
            out.put("message", "已认出这条线。请把它插回去。");
        } else if (removed.isEmpty()) {
            out.put("ok", false);
            out.put("message", "没有发现消失的端口。请确认已经拔掉那条线，再试一次。");
        } else {
            out.put("ok", false);
            out.put("removed", removed);
            out.put("message", "同时消失了多个端口。请一次只拔一条线。");
        }
        return out;
    }

    /** After user plugs the cable back, detect the returned port. */
    public Map<String, Object> confirmReconnect(String expectedPath) {
        List<Map<String, Object>> ports = listPorts();
        List<String> now = ports.stream().map(p -> String.valueOf(p.get("path"))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ports", ports);
        if (expectedPath != null && now.contains(expectedPath)) {
            out.put("ok", true);
            out.put("port", describe(Path.of(expectedPath).getFileName().toString()));
            out.put("message", "已插回，继续下一步。");
            baseline = now;
            return out;
        }
        List<String> added = now.stream().filter(p -> !baseline.contains(p)).toList();
        if (added.size() == 1) {
            String path = added.get(0);
            out.put("ok", true);
            out.put("port", describe(Path.of(path).getFileName().toString()));
            out.put("message", "已检测到重新插入的设备。");
            baseline = now;
            return out;
        }
        out.put("ok", false);
        out.put("message", added.isEmpty()
                ? "还没检测到插回。请把刚才拔下的那根 USB 插紧，再点一次。"
                : "检测到多根线同时插回。请只插回刚才那一根。");
        return out;
    }

    private boolean isCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("bluetooth") || lower.contains("debug-console") || lower.equals("tty")) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> dedupeMacTwins(List<Map<String, Object>> ports) {
        List<String> cuSuffixes = ports.stream()
                .map(p -> String.valueOf(p.get("name")))
                .filter(n -> n.startsWith("cu."))
                .map(n -> n.substring(3))
                .toList();
        if (cuSuffixes.isEmpty()) {
            return ports;
        }
        return ports.stream()
                .filter(p -> {
                    String name = String.valueOf(p.get("name"));
                    return !(name.startsWith("tty.") && cuSuffixes.contains(name.substring(4)));
                })
                .toList();
    }

    private Map<String, Object> describe(String name) {
        String path = "/dev/" + name;
        String serialId = extractSerialId(name);
        String tail = serialId.length() >= 4 ? serialId.substring(serialId.length() - 4) : serialId;
        String kind;
        if (name.contains("usbmodem")) {
            kind = "USB 串口设备";
        } else if (name.contains("usbserial") || name.contains("wchusbserial")) {
            kind = "USB 转串口";
        } else if (name.startsWith("ttyACM") || name.startsWith("ttyUSB")) {
            kind = "Linux 串口";
        } else {
            kind = "串口设备";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("name", name);
        m.put("serialId", serialId);
        m.put("stableId", "arm_" + serialId);
        m.put("label", kind + " · " + serialId);
        m.put("detail", "稳定编号 " + serialId + "（换 USB 口后仍可自动认回）。尾号 " + tail);
        return m;
    }

    /**
     * Stable hardware id from macOS/Linux USB serial path names, e.g.
     * {@code cu.usbmodem5B421351081} → {@code 5B421351081}.
     */
    static String extractSerialId(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot >= 0 && dot < base.length() - 1) {
            base = base.substring(dot + 1);
        }
        // usbmodem5B421351081 / usbserial-1420 / ttyUSB0
        String digits = base.replaceAll("(?i)^(?:usbmodem|usbserial|wchusbserial|ttyACM|ttyUSB|-)+", "");
        digits = digits.replaceAll("[^A-Za-z0-9]", "");
        if (digits.isEmpty()) {
            digits = base.replaceAll("[^A-Za-z0-9]", "");
        }
        return digits.isEmpty() ? base : digits;
    }
}
