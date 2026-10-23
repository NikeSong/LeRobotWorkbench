package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lerobot.workbench.config.WorkbenchProperties;

/**
 * Resolves {@code uv} / python even when the Spring Boot process has a minimal PATH
 * (IDE launches often omit {@code ~/.local/bin}).
 */
@Component
public class ProcessCommandResolver {

    private static final Logger log = LoggerFactory.getLogger(ProcessCommandResolver.class);

    private final WorkbenchProperties props;
    private final AtomicBoolean workbenchDepsReady = new AtomicBoolean(false);
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    public ProcessCommandResolver(WorkbenchProperties props) {
        this.props = props;
    }

    public Path repoRoot() {
        return Path.of(props.getRepoRoot()).toAbsolutePath().normalize();
    }

    /** Prefer project-local uv-managed venv, then configured bin, then well-known uv paths. */
    public ResolvedUv resolveUv() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>();
        String configured = props.getPythonBin();
        if (configured != null && !configured.isBlank() && !"uv".equals(configured)) {
            candidates.add(Path.of(configured));
        }
        candidates.add(home.resolve(".local/bin/uv"));
        candidates.add(Path.of("/opt/homebrew/bin/uv"));
        candidates.add(Path.of("/usr/local/bin/uv"));
        candidates.add(home.resolve(".cargo/bin/uv"));
        for (Path c : candidates) {
            if (Files.isExecutable(c)) {
                return new ResolvedUv(c.toString(), true, c.toString());
            }
        }
        return new ResolvedUv("uv", false, "uv not found in ~/.local/bin or Homebrew");
    }

    public List<String> lerobotCli(String scriptName, String... extraArgs) {
        List<String> cmd = new ArrayList<>(pythonLauncher());
        cmd.add(scriptName);
        for (String a : extraArgs) {
            if (a != null && !a.isBlank()) {
                cmd.add(a);
            }
        }
        return cmd;
    }

    /**
     * Prefer {@code .venv/bin/python} so Workbench never blocks on a stuck
     * {@code uv sync} holding {@code .venv/.lock}. Falls back to
     * {@code uv run --no-sync python}.
     */
    public List<String> pythonLauncher() {
        Path venvPy = repoRoot().resolve(".venv/bin/python");
        if (Files.isRegularFile(venvPy) || Files.isSymbolicLink(venvPy)) {
            return List.of(venvPy.toAbsolutePath().normalize().toString());
        }
        ResolvedUv uv = resolveUv();
        if (!uv.found()) {
            return List.of("python3");
        }
        return List.of(uv.executable(), "run", "--no-sync", "python");
    }

    /** Prefix for launching a bridge script under the project venv. */
    public List<String> pythonScript(Path script) {
        List<String> cmd = new ArrayList<>(pythonLauncher());
        cmd.add(script.toAbsolutePath().normalize().toString());
        return cmd;
    }

    public Map<String, String> processEnvironment() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String path = env.getOrDefault("PATH", "");
        String home = System.getProperty("user.home");
        String prefix = home + "/.local/bin:/opt/homebrew/bin:/usr/local/bin:";
        if (!path.contains(home + "/.local/bin")) {
            env.put("PATH", prefix + path);
        }
        // Never let child `uv run` wait on network/lock sync.
        env.put("UV_NO_SYNC", "1");
        env.put("PYTHONUNBUFFERED", "1");
        // Recordings must land where the dataset page scans.
        Path dataHome = Path.of(props.getDataHome()).toAbsolutePath().normalize();
        env.put("HF_LEROBOT_HOME", dataHome.toString());
        env.remove("LEROBOT_HOME");
        return env;
    }

    /**
     * Fast check that Workbench Python extras exist. Uses filesystem only (no {@code uv run})
     * so HTTP handlers never stall for tens of seconds.
     * If missing, kicks off a background {@code uv sync --extra workbench} once.
     */
    public Map<String, Object> ensureWorkbenchPythonDeps() {
        if (workbenchDepsReady.get() || workbenchPackagesOnDisk()) {
            workbenchDepsReady.set(true);
            return Map.of("ok", true, "message", "Workbench Python 依赖已就绪");
        }
        if (syncInProgress.get()) {
            return Map.of(
                    "ok",
                    false,
                    "message",
                    "正在后台安装 Python 依赖（uv sync --extra workbench），请等 1–2 分钟后再点一次。也可手动执行: bash workbench/setup_python.sh");
        }
        ResolvedUv uv = resolveUv();
        if (!uv.found()) {
            return Map.of(
                    "ok",
                    false,
                    "message",
                    "缺少 datasets 依赖，且未找到 uv。请安装 uv 后执行: uv sync --extra workbench");
        }
        if (syncInProgress.compareAndSet(false, true)) {
            Thread t = new Thread(() -> syncWorkbenchExtra(uv), "uv-sync-workbench");
            t.setDaemon(true);
            t.start();
        }
        return Map.of(
                "ok",
                false,
                "message",
                "缺少 datasets 等依赖，已开始后台安装（uv sync --extra workbench）。"
                        + "请等安装完成后再点「开始录制」。也可手动: bash workbench/setup_python.sh");
    }

    private void syncWorkbenchExtra(ResolvedUv uv) {
        try {
            log.warn("后台安装 Workbench 依赖: uv sync --extra workbench");
            ProcessBuilder pb = new ProcessBuilder(uv.executable(), "sync", "--extra", "workbench");
            pb.directory(repoRoot().toFile());
            pb.environment().putAll(processEnvironment());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var in = p.getInputStream()) {
                in.transferTo(System.out);
            }
            boolean finished = p.waitFor(20, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                log.error("uv sync --extra workbench 超时");
                return;
            }
            if (p.exitValue() == 0 && workbenchPackagesOnDisk()) {
                workbenchDepsReady.set(true);
                log.info("Workbench Python 依赖安装完成");
            } else {
                log.error("uv sync --extra workbench 失败，exit={}", p.exitValue());
            }
        } catch (Exception e) {
            log.error("后台 uv sync 失败", e);
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * Instant check: datasets + feetech (scservo_sdk) present under
     * {@code .venv/.../site-packages}.
     */
    boolean workbenchPackagesOnDisk() {
        return sitePackagePresent("datasets/__init__.py")
                && sitePackagePresent("scservo_sdk/__init__.py");
    }

    private boolean sitePackagePresent(String relativeUnderSitePackages) {
        Path venv = repoRoot().resolve(".venv");
        Path lib = venv.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return false;
        }
        try (Stream<Path> pythons = Files.list(lib)) {
            return pythons
                    .filter(Files::isDirectory)
                    .map(p -> p.resolve("site-packages").resolve(relativeUnderSitePackages))
                    .anyMatch(Files::isRegularFile);
        } catch (Exception e) {
            return false;
        }
    }

    public record ResolvedUv(String executable, boolean found, String detail) {}
}
