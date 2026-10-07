package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 测试造数工具：在临时目录里按 env 准备 desired.json / observed.jsonl，
 * 并返回直接指向该目录的 {@link FileStateStore}，测试间互不污染。
 */
public final class TestStateFixtures {

    private TestStateFixtures() {
    }

    public static Path newDataRoot() {
        try {
            return Files.createTempDirectory("drift-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static FileStateStore store(Path dataRoot) {
        return new FileStateStore(dataRoot.toString());
    }

    public static void writeDesired(Path root, String env, String content) {
        write(root.resolve(env).resolve("desired.json"), content);
    }

    public static void writeObserved(Path root, String env, List<String> lines) {
        write(root.resolve(env).resolve("observed.jsonl"), String.join("\n", lines) + "\n");
    }

    public static void writeObservedRaw(Path root, String env, String raw) {
        write(root.resolve(env).resolve("observed.jsonl"), raw);
    }

    public static String readObserved(Path root, String env) {
        try {
            return Files.readString(root.resolve(env).resolve("observed.jsonl"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 常用期望：3 个无依赖资源，版本 v1，内容与 id 对齐。 */
    public static String desiredThreeResources() {
        return """
                [
                  {"id":"svc-a","type":"service","version":"v1","desiredValue":"a@100"},
                  {"id":"svc-b","type":"service","version":"v1","desiredValue":"b@100"},
                  {"id":"svc-c","type":"config","version":"v1","desiredValue":"c@100"}
                ]
                """;
    }

    public static String observedLine(String id, String type, String version, String value) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"version\":\"" + version
                + "\",\"observedValue\":\"" + value + "\"}";
    }
}
