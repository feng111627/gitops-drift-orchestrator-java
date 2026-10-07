package com.github.feng111627.gitopsdriftorchestratorjava.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的 {@link StateStore}。
 *
 * <pre>
 * dataRoot/
 *   &lt;env&gt;/
 *     desired.json                 期望状态（JSON 数组）
 *     observed.jsonl               实际状态（每行一条 JSON 观测）
 *     sync.lock                    同步互斥锁（持有期间存在）
 *     snapshots/snap-000001.json   历史对账快照
 *     rollouts/&lt;resourceId&gt;.json   渐进发布记录
 *     syncs/&lt;executionId&gt;.json     同步执行记录
 * </pre>
 * 锁使用 {@link FileChannel#tryLock()} 实现跨触发（含跨进程）互斥，
 * 锁文件内容记录持有者 executionId，便于诊断与防止误释放。
 */
@Repository
public class FileStateStore implements StateStore {

    private final Path dataRoot;

    /** env -> 持有的锁通道；显式释放时关闭，防止文件描述符泄漏。 */
    private final Map<String, FileChannel> heldLocks = new ConcurrentHashMap<>();

    public FileStateStore(@Value("${drift.data-root:./data}") String dataRoot) {
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    private static String safe(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("非法的路径标识: " + name);
        }
        return name;
    }

    private Path envDir(String env) {
        return dataRoot.resolve(safe(env));
    }

    private Path desiredFile(String env) {
        return envDir(env).resolve("desired.json");
    }

    private Path observedFile(String env) {
        return envDir(env).resolve("observed.jsonl");
    }

    private Path snapshotsDir(String env) {
        return envDir(env).resolve("snapshots");
    }

    private Path rolloutsDir(String env) {
        return envDir(env).resolve("rollouts");
    }

    private Path syncsDir(String env) {
        return envDir(env).resolve("syncs");
    }

    private Path lockFile(String env) {
        return envDir(env).resolve("sync.lock");
    }

    private static String readStringIfExists(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + path, e);
        }
    }

    /** 原子写：先写临时文件再 move，避免读到半截内容。 */
    private static void writeAtomically(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), ".tmp-", ".part");
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + path, e);
        }
    }

    @Override
    public String readDesired(String env) {
        String content = readStringIfExists(desiredFile(env));
        return content == null ? "[]" : content;
    }

    @Override
    public void writeDesired(String env, String content) {
        writeAtomically(desiredFile(env), content);
    }

    @Override
    public String readObserved(String env) {
        String content = readStringIfExists(observedFile(env));
        return content == null ? "" : content;
    }

    @Override
    public void writeObserved(String env, String content) {
        writeAtomically(observedFile(env), content);
    }

    @Override
    public synchronized String saveReconcileSnapshot(String env, String reportJson) {
        Path dir = snapshotsDir(env);
        long next = 1;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> paths = Files.list(dir)) {
                next = paths.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith("snap-") && n.endsWith(".json"))
                        .map(n -> n.substring(5, n.length() - 5))
                        .filter(n -> n.matches("\\d+"))
                        .mapToLong(Long::parseLong)
                        .max().orElse(0) + 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列举快照失败", e);
        }
        String snapshotId = String.format("snap-%06d", next);
        writeAtomically(dir.resolve(snapshotId + ".json"), reportJson);
        return snapshotId;
    }

    @Override
    public String readReconcileSnapshot(String env, String snapshotId) {
        return readStringIfExists(snapshotsDir(env).resolve(safe(snapshotId) + ".json"));
    }

    @Override
    public List<String> listReconcileSnapshots(String env) {
        Path dir = snapshotsDir(env);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("snap-") && n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列举快照失败", e);
        }
    }

    @Override
    public boolean tryAcquireSyncLock(String env, String executionId) {
        Path lock = lockFile(env);
        try {
            Files.createDirectories(lock.getParent());
            // CREATE_NEW + OS FileLock：同机同 env 同时只有一个持有者（含跨进程触发）
            FileChannel channel = FileChannel.open(lock,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                channel.close();
                Files.deleteIfExists(lock);
                return false;
            }
            channel.write(StandardCharsets.UTF_8.encode(
                    "{\"executionId\":\"" + safe(executionId) + "\"}"));
            channel.force(true);
            heldLocks.put(env, channel);
            return true;
        } catch (IOException exists) {
            return false;
        }
    }

    @Override
    public void releaseSyncLock(String env, String executionId) {
        FileChannel channel = heldLocks.remove(env);
        if (channel != null) {
            try {
                channel.close(); // 同时释放 OS FileLock
            } catch (IOException e) {
                throw new UncheckedIOException("关闭锁通道失败: " + lockFile(env), e);
            }
        }
        try {
            Files.deleteIfExists(lockFile(env));
        } catch (IOException e) {
            throw new UncheckedIOException("释放锁失败: " + lockFile(env), e);
        }
    }

    @Override
    public String readRollout(String env, String resourceId) {
        return readStringIfExists(rolloutsDir(env).resolve(safe(resourceId) + ".json"));
    }

    @Override
    public void writeRollout(String env, String resourceId, String json) {
        writeAtomically(rolloutsDir(env).resolve(safe(resourceId) + ".json"), json);
    }

    @Override
    public void saveSyncExecution(String env, String executionId, String json) {
        writeAtomically(syncsDir(env).resolve(safe(executionId) + ".json"), json);
    }
}
