package com.lerobot.workbench.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lerobot.workbench.config.WorkbenchProperties;

@Service
public class DatasetLibraryService {

    private static final DateTimeFormatter NAME_TIME =
            DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault());

    private final WorkbenchProperties properties;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> sideLocks = new ConcurrentHashMap<>();

    public DatasetLibraryService(WorkbenchProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private Path resolveRepo(String repoId) {
        if (repoId == null || repoId.isBlank() || repoId.contains("..")) {
            throw new IllegalArgumentException("数据集名称无效: " + repoId);
        }
        Path root = Path.of(repoId);
        if (!root.isAbsolute()) {
            root = dataHome().resolve(repoId).normalize();
        } else {
            root = root.toAbsolutePath().normalize();
        }
        if (!isUnderAllowedRoot(root)) {
            throw new IllegalArgumentException("数据集不在允许的目录里: " + repoId);
        }
        return root;
    }

    private boolean isUnderAllowedRoot(Path root) {
        Path normal = root.toAbsolutePath().normalize();
        if (normal.startsWith(dataHome())) {
            return true;
        }
        for (Path allowed : scanRoots()) {
            if (normal.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        Path extra = dataHome().resolve(".workbench-scan-roots");
        if (!Files.isRegularFile(extra)) {
            return roots;
        }
        try {
            for (String line : Files.readAllLines(extra)) {
                if (line.isBlank()) {
                    continue;
                }
                Path parent = Path.of(line.trim()).toAbsolutePath().normalize();
                if (!isDangerousRoot(parent)) {
                    roots.add(parent);
                }
            }
        } catch (IOException ignored) {
            // keep the default home only
        }
        return roots;
    }

    private static boolean isDangerousRoot(Path parent) {
        Path normal = parent.toAbsolutePath().normalize();
        if (normal.getNameCount() < 2) {
            return true;
        }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return normal.equals(home)
                || normal.equals(Path.of("/Users"))
                || normal.equals(Path.of("/home"))
                || normal.equals(Path.of("/tmp"))
                || normal.equals(Path.of("/private/tmp"));
    }

    public Path dataHome() {
        return Path.of(properties.getDataHome()).toAbsolutePath().normalize();
    }

    public Path datasetRoot(String requested, String folder) {
        Path root;
        if (requested == null || requested.isBlank()) {
            root = dataHome().resolve("local").resolve(folder);
        } else {
            root = Path.of(requested).toAbsolutePath().normalize();
            if (isDangerousRoot(root)) {
                throw new IllegalArgumentException("保存位置太宽: " + requested);
            }
            if (root.getFileName() == null || !root.getFileName().toString().equals(folder)) {
                root = root.resolve(folder);
            }
        }
        return root.normalize();
    }

    public String repoIdFor(Path root) {
        Path normal = root.toAbsolutePath().normalize();
        Path home = dataHome();
        if (normal.startsWith(home)) {
            return home.relativize(normal).toString().replace('\\', '/');
        }
        return normal.toString();
    }

    public void remember(Path datasetRoot) {
        Path parent = datasetRoot.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.startsWith(dataHome()) || isDangerousRoot(parent)) {
            return;
        }
        Path file = dataHome().resolve(".workbench-scan-roots");
        try {
            Files.createDirectories(dataHome());
            java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
            if (Files.isRegularFile(file)) {
                lines.addAll(Files.readAllLines(file));
            }
            lines.add(parent.toString());
            Files.writeString(file, String.join("\n", lines) + "\n");
        } catch (IOException ignored) {
            // listing still works for the default cache
        }
    }

    public Map<String, Object> list() {
        List<Map<String, Object>> datasets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        collect(dataHome(), 6, datasets, seen);
        for (Path parent : scanRoots()) {
            collect(parent, 3, datasets, seen);
        }
        datasets.sort(Comparator.comparingLong((Map<String, Object> d) -> ((Number) d.get("mtime")).longValue()).reversed());
        return Map.of("ok", true, "datasets", datasets);
    }

    private void collect(Path home, int depth, List<Map<String, Object>> datasets, java.util.Set<String> seen) {
        if (!Files.isDirectory(home)) {
            return;
        }
        try (Stream<Path> infos = Files.walk(home, depth)) {
            infos.filter(p -> p.getFileName().toString().equals("info.json") && p.getParent() != null && p.getParent().getFileName().toString().equals("meta"))
                    .filter(p -> !p.toString().contains("/hub/"))
                    .filter(p -> !p.toString().contains("/calibration/"))
                    .forEach(info -> {
                        Path root = info.getParent().getParent();
                        if (seen.add(root.toAbsolutePath().normalize().toString())) {
                            datasets.add(describe(root));
                        }
                    });
        } catch (IOException ignored) {
            // skip unreadable roots
        }
    }

    public Map<String, Object> saveAll() {
        Object rows = list().get("datasets");
        int saved = 0;
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> map) || map.get("repoId") == null) {
                    continue;
                }
                save(String.valueOf(map.get("repoId")));
                saved++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("saved", saved);
        out.put("message", saved == 0 ? "列表里没有可保存的采集" : "已保存 " + saved + " 条采集");
        return out;
    }

    public Map<String, Object> save(String repoId) {
        Path root = require(repoId);
        Map<String, Object> side = readSide(root);
        side.put("saved", true);
        if (!side.containsKey("displayName")) {
            side.put("displayName", defaultName(root));
        }
        writeSide(root, side);
        return describe(root);
    }

    public Map<String, Object> rename(String repoId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 80) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", "请输入 1 到 80 个字的名称。");
            return out;
        }
        Path root = require(repoId);
        Map<String, Object> side = readSide(root);
        side.put("displayName", name);
        side.putIfAbsent("saved", false);
        writeSide(root, side);
        Map<String, Object> out = describe(root);
        out.put("ok", true);
        return out;
    }

    public Map<String, Object> delete(String repoId) {
        Path root = require(repoId);
        try {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            return Map.of("ok", false, "message", "删除失败: " + e.getMessage());
        }
        return Map.of("ok", true, "message", "已删除 " + repoId);
    }

    public void markDraft(String repoId, String task) {
        if (repoId == null || repoId.isBlank()) {
            return;
        }
        Path root = resolveRepo(repoId);
        if (!Files.isRegularFile(root.resolve("meta/info.json"))) {
            return;
        }
        Map<String, Object> side = readSide(root);
        side.putIfAbsent("saved", false);
        if (task != null && !task.isBlank()) {
            side.put("task", task);
            side.putIfAbsent("displayName", task.trim());
        }
        side.putIfAbsent("displayName", defaultName(root));
        writeSide(root, side);
    }

    public void recordEpisode(String repoId, String task, int episodeIndex) {
        markDraft(repoId, task);
        Path root;
        try {
            root = resolveRepo(repoId);
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isRegularFile(root.resolve("meta/info.json"))) {
            return;
        }
        Map<String, Object> side = readSide(root);
        List<Map<String, Object>> episodes = new ArrayList<>();
        Object raw = side.get("episodes");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    episodes.add(copy);
                }
            }
        }
        String pascal = TaskRepoIds.pascal(task);
        if (pascal.isBlank()) {
            pascal = "Task";
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
        String name = pascal + "_" + stamp + "_" + (episodeIndex + 1);
        boolean replaced = false;
        for (Map<String, Object> episode : episodes) {
            if (num(episode.get("index")) == episodeIndex) {
                episode.put("name", name);
                replaced = true;
            }
        }
        if (!replaced) {
            Map<String, Object> episode = new LinkedHashMap<>();
            episode.put("index", episodeIndex);
            episode.put("name", name);
            episodes.add(episode);
        }
        side.put("episodes", episodes);
        if (task != null && !task.isBlank()) {
            side.put("task", task);
        }
        writeSide(root, side);
    }

    public Path require(String repoId) {
        Path root = resolveRepo(repoId);
        if (!Files.isRegularFile(root.resolve("meta/info.json"))) {
            throw new IllegalArgumentException("找不到数据集 " + repoId);
        }
        return root;
    }

    public Path media(String repoId, String relative) {
        if (relative == null || relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
            throw new IllegalArgumentException("文件路径无效。");
        }
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".mp4") && !lower.endsWith(".webm")) {
            throw new IllegalArgumentException("只允许读取回合视频。");
        }
        try {
            Path root = require(repoId).toRealPath();
            Path file = root.resolve(relative).normalize().toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("找不到媒体文件。");
            }
            return file;
        } catch (IOException e) {
            throw new IllegalArgumentException("找不到媒体文件。");
        }
    }

    private Map<String, Object> describe(Path root) {
        Map<String, Object> info = readJson(root.resolve("meta/info.json"));
        Map<String, Object> side = readSide(root);
        int episodes = num(info.get("total_episodes"));
        int frames = num(info.get("total_frames"));
        String repoId = repoIdFor(root);
        List<String> videos = new ArrayList<>();
        Path videoDir = root.resolve("videos");
        if (Files.isDirectory(videoDir)) {
            try (Stream<Path> walk = Files.walk(videoDir, 5)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".mp4"))
                        .map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .forEach(videos::add);
            } catch (IOException ignored) {
                // leave empty
            }
            videos.sort((a, b) -> {
                int sa = scoreCam(a);
                int sb = scoreCam(b);
                return Integer.compare(sa, sb);
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("repoId", repoId);
        out.put("displayName", String.valueOf(side.getOrDefault("displayName", defaultName(root))));
        out.put("saved", Boolean.TRUE.equals(side.get("saved")));
        out.put("task", side.getOrDefault("task", ""));
        out.put("episodes", episodes);
        out.put("frames", frames);
        out.put("fps", info.getOrDefault("fps", 0));
        out.put("videos", videos);
        out.put("empty", episodes <= 0);
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(root.resolve("meta/info.json")).toMillis();
        } catch (IOException e) {
            mtime = 0L;
        }
        out.put("mtime", mtime);
        out.put("takes", takeNames(side, episodes, mtime));
        return out;
    }

    private List<Map<String, Object>> takeNames(Map<String, Object> side, int episodeCount, long mtime) {
        List<Map<String, Object>> takes = new ArrayList<>();
        Object raw = side.get("episodes");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || map.get("name") == null) {
                    continue;
                }
                int index = num(map.get("index"));
                if (index < 0 || index >= episodeCount) {
                    continue;
                }
                Map<String, Object> take = new LinkedHashMap<>();
                take.put("index", index);
                take.put("name", String.valueOf(map.get("name")));
                takes.add(take);
            }
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Map<String, Object> take : takes) {
            seen.add(num(take.get("index")));
        }
        if (episodeCount <= 0) {
            return takes;
        }
        String pascal = TaskRepoIds.pascal(String.valueOf(side.getOrDefault("task", "")));
        if (pascal.isBlank()) {
            pascal = "Task";
        }
        String stamp = mtime > 0
                ? DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(mtime))
                : "00000000_000000";
        for (int i = 0; i < episodeCount; i++) {
            if (seen.contains(i)) {
                continue;
            }
            Map<String, Object> take = new LinkedHashMap<>();
            take.put("index", i);
            take.put("name", pascal + "_" + stamp + "_" + (i + 1));
            takes.add(take);
        }
        takes.sort(Comparator.comparingInt(t -> num(t.get("index"))));
        return takes;
    }

    private Object sideLock(Path root) {
        return sideLocks.computeIfAbsent(root.toAbsolutePath().normalize().toString(), key -> new Object());
    }

    private Map<String, Object> readSide(Path root) {
        synchronized (sideLock(root)) {
            Path side = root.resolve("meta/workbench.json");
            if (!Files.isRegularFile(side)) {
                return new LinkedHashMap<>();
            }
            try {
                return mapper.readValue(side.toFile(), new TypeReference<>() {});
            } catch (IOException e) {
                throw new IllegalStateException("采集信息损坏，已停止以免覆盖: " + side);
            }
        }
    }

    private void writeSide(Path root, Map<String, Object> side) {
        synchronized (sideLock(root)) {
            try {
                Files.createDirectories(root.resolve("meta"));
                Path target = root.resolve("meta/workbench.json");
                Path tmp = root.resolve("meta/workbench.json.tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), side);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                try {
                    Files.move(
                            root.resolve("meta/workbench.json.tmp"),
                            root.resolve("meta/workbench.json"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException inner) {
                    throw new IllegalStateException("无法写入采集信息: " + inner.getMessage(), inner);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法写入采集信息: " + e.getMessage(), e);
            }
        }
    }

    private Map<String, Object> readJson(Path file) {
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static String defaultName(Path root) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(root.resolve("meta/info.json")).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        return "采集 " + NAME_TIME.format(Instant.ofEpochMilli(mtime));
    }

    private static int scoreCam(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("front") || lower.contains("main")) {
            return 0;
        }
        if (lower.contains("wrist")) {
            return 1;
        }
        return 2;
    }

    private static int num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
