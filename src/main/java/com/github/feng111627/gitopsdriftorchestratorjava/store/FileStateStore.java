package com.github.feng111627.gitopsdriftorchestratorjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.feng111627.gitopsdriftorchestratorjava.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 基于本地文件的 {@link StateStore} 实现。
 *
 * <p>目录结构（baseDir 可配，默认工作目录下 state/）：
 * <pre>
 * baseDir/&lt;env&gt;/desired.json               期望状态
 * baseDir/&lt;env&gt;/observed.json              观测（实际）状态，保留原始 JSON
 * baseDir/&lt;env&gt;/reports/&lt;reportId&gt;.json    对账报告归档（内容寻址、只追加）
 * baseDir/&lt;env&gt;/sync-records/&lt;runId&gt;.json  同步执行记录
 * baseDir/&lt;env&gt;/releases/&lt;releaseId&gt;.json  发布记录
 * baseDir/snapshots/&lt;releaseId&gt;.json       发布前观测快照（回滚目标）
 * </pre>
 */
public class FileStateStore implements StateStore {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileStateStore(Path baseDir) {
        this.baseDir = baseDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建状态目录: " + baseDir, e);
        }
    }

    private Path envDir(String environment) {
        return baseDir.resolve(safe(environment));
    }

    private static String safe(String environment) {
        if (environment == null || environment.isBlank() || !environment.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法环境名: " + environment);
        }
        return environment;
    }

    private Path file(String environment, String name) {
        Path p = envDir(environment).resolve(name);
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private <T> T readJson(Path path, TypeReference<T> type, T fallback) {
        if (!Files.exists(path)) {
            return fallback;
        }
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + path, e);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败: " + path, e);
        }
    }

    @Override
    public List<DesiredResource> loadDesired(String environment) {
        return readJson(file(environment, "desired.json"),
                new TypeReference<List<DesiredResource>>() {
                }, new ArrayList<>());
    }

    @Override
    public List<Map<String, Object>> loadObservedRaw(String environment) {
        return readJson(file(environment, "observed.json"),
                new TypeReference<List<Map<String, Object>>>() {
                }, new ArrayList<>());
    }

    @Override
    public void saveDesired(String environment, List<DesiredResource> desired) {
        writeJson(file(environment, "desired.json"), desired);
    }

    @Override
    public void saveObservedRaw(String environment, List<Map<String, Object>> observed) {
        writeJson(file(environment, "observed.json"), observed);
    }

    @Override
    public synchronized String archiveReport(String environment, ReconcileReport report) {
        Path path = file(environment, "reports/" + report.reportId() + ".json");
        if (!Files.exists(path)) {
            // 报告只追加、不可变；同内容（同 reportId）重复归档保持首份时间戳
            writeJson(path, report);
        }
        return report.reportId();
    }

    @Override
    public Optional<ReconcileReport> loadReport(String environment, String reportId) {
        Path path = file(environment, "reports/" + safe(reportId) + ".json");
        return Files.exists(path)
                ? Optional.of(readJson(path, new TypeReference<ReconcileReport>() {
        }, null))
                : Optional.empty();
    }

    @Override
    public List<ReconcileReport> listReports(String environment) {
        return listSingles(envDir(environment).resolve("reports"),
                new TypeReference<ReconcileReport>() {
                });
    }

    @Override
    public void saveSyncRecord(SyncRecord record) {
        writeJson(file(record.environment(), "sync-records/" + record.runId() + ".json"), record);
    }

    @Override
    public List<SyncRecord> listSyncRecords(String environment) {
        return listSingles(envDir(environment).resolve("sync-records"),
                new TypeReference<SyncRecord>() {
                });
    }

    @Override
    public void saveReleaseRecord(ReleaseRecord record) {
        writeJson(file(record.environment(), "releases/" + record.releaseId() + ".json"), record);
    }

    @Override
    public List<ReleaseRecord> listReleaseRecords(String environment) {
        List<ReleaseRecord> all = listSingles(envDir(environment).resolve("releases"),
                new TypeReference<ReleaseRecord>() {
                });
        all.sort(Comparator.comparing(ReleaseRecord::createdAt));
        return all;
    }

    @Override
    public void saveReleaseSnapshot(String releaseId, List<Map<String, Object>> observed) {
        writeJson(baseDir.resolve("snapshots").resolve(safe(releaseId) + "-observed.json"), observed);
    }

    @Override
    public Optional<List<Map<String, Object>>> loadReleaseSnapshot(String releaseId) {
        Path path = baseDir.resolve("snapshots").resolve(safe(releaseId) + "-observed.json");
        return Files.exists(path)
                ? Optional.of(readJson(path, new TypeReference<List<Map<String, Object>>>() {
        }, new ArrayList<>()))
                : Optional.empty();
    }

    @Override
    public void saveReleaseDesiredSnapshot(String releaseId, List<DesiredResource> desired) {
        writeJson(baseDir.resolve("snapshots").resolve(safe(releaseId) + "-desired.json"), desired);
    }

    @Override
    public Optional<List<DesiredResource>> loadReleaseDesiredSnapshot(String releaseId) {
        Path path = baseDir.resolve("snapshots").resolve(safe(releaseId) + "-desired.json");
        return Files.exists(path)
                ? Optional.of(readJson(path, new TypeReference<List<DesiredResource>>() {
        }, new ArrayList<>()))
                : Optional.empty();
    }

    private <T> List<T> listSingles(Path dir, TypeReference<T> type) {
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            List<T> result = new ArrayList<>();
            List<Path> files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path p : files) {
                result.add(mapper.readValue(p.toFile(), type));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("列出目录失败: " + dir, e);
        }
    }

    private <T> List<T> listDir(Path dir, TypeReference<List<T>> type) {
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            List<T> result = new ArrayList<>();
            List<Path> files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path p : files) {
                result.addAll(mapper.readValue(p.toFile(), type));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("列出目录失败: " + dir, e);
        }
    }
}
