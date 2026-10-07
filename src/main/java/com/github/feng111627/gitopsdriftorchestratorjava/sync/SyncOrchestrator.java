package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceDescriptor;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncExecution;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncState;
import com.github.feng111627.gitopsdriftorchestratorjava.model.WaveRecord;
import com.github.feng111627.gitopsdriftorchestratorjava.model.WaveState;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 同步编排。
 *
 * <p>约定：
 * <ol>
 *   <li>波次：对期望状态的 dependsOn 做拓扑分层（Kahn 算法），第 0 波无依赖，
 *       第 N 波依赖第 N-1 或更早波次；引用不存在的依赖、依赖成环属
 *       {@link FailureReason#DEPENDENCY_NOT_READY}，在任何变更前直接失败。</li>
 *   <li>健康门禁：每波 apply 后必须通过 {@link HealthChecker}，否则以
 *       {@link FailureReason#HEALTH_TIMEOUT} 中断，后续波次不再执行。</li>
 *   <li>并发互斥：同一环境同时只允许一个同步，第二个触发立即被拒绝
 *       （{@link FailureReason#CONCURRENT_SYNC_REJECTED}），不排队、不交叉。</li>
 *   <li>失败语义：中途失败立即停止；已 apply 并通过健康检查的波次保留在实际状态中，
 *       执行记录逐波写明 APPLIED / HEALTH_PASSED / FAILED / SKIPPED。</li>
 * </ol>
 */
@Service
public class SyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrator.class);

    private final StateStore stateStore;
    private final HealthChecker healthChecker;
    private final ReconcileService reconcileService;
    private final long healthTimeoutMillis;

    public SyncOrchestrator(StateStore stateStore,
                            HealthChecker healthChecker,
                            ReconcileService reconcileService,
                            @Value("${drift.health.timeout-ms:500}") long healthTimeoutMillis) {
        this.stateStore = stateStore;
        this.healthChecker = healthChecker;
        this.reconcileService = reconcileService;
        this.healthTimeoutMillis = healthTimeoutMillis;
    }

    /** 依赖拓扑分层。 */
    static List<List<String>> planWaves(List<ResourceDescriptor> desired) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (ResourceDescriptor d : desired) {
            deps.put(d.id(), new HashSet<>(d.dependsOn()));
        }
        // 引用不存在的依赖：依赖永远不会就绪
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                if (!deps.containsKey(dep)) {
                    throw new SyncException(FailureReason.DEPENDENCY_NOT_READY,
                            "资源 " + e.getKey() + " 依赖的资源不存在: " + dep);
                }
            }
        }
        List<List<String>> waves = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        while (placed.size() < deps.size()) {
            List<String> current = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
                if (placed.contains(e.getKey())) {
                    continue;
                }
                if (placed.containsAll(e.getValue())) {
                    current.add(e.getKey());
                }
            }
            if (current.isEmpty()) {
                List<String> cyclic = deps.keySet().stream()
                        .filter(k -> !placed.contains(k)).sorted().toList();
                throw new SyncException(FailureReason.DEPENDENCY_NOT_READY,
                        "资源依赖存在环，无法划分波次，涉及资源: " + cyclic);
            }
            current.sort(String.CASE_INSENSITIVE_ORDER);
            waves.add(List.copyOf(current));
            placed.addAll(current);
        }
        return waves;
    }

    /**
     * 触发一次环境同步。
     */
    public SyncExecution trigger(String env) {
        String executionId = "sync-" + UUID.randomUUID();
        long startedAt = System.currentTimeMillis();
        if (!stateStore.tryAcquireSyncLock(env, executionId)) {
            throw new SyncException(FailureReason.CONCURRENT_SYNC_REJECTED,
                    "环境 " + env + " 已有同步正在执行，本次触发被拒绝（不允许交叉执行）");
        }
        List<WaveRecord> waveRecords = new ArrayList<>();
        SyncState state = SyncState.SUCCEEDED;
        FailureReason reason = null;
        String failureDetail = null;
        log.info("同步开始 env={} executionId={}", env, executionId);
        try {
            // 1) 解析 + 波次规划：任何校验/依赖问题都在变更前失败
            List<ResourceDescriptor> desired;
            List<List<String>> waves;
            try {
                desired = reconcileService.parseDesired(env);
                waves = planWaves(desired);
            } catch (SyncException e) {
                throw e;
            }
            Map<String, ResourceDescriptor> byId = new LinkedHashMap<>();
            desired.forEach(d -> byId.put(d.id(), d));

            for (int w = 0; w < waves.size(); w++) {
                List<String> wave = waves.get(w);
                // 2) apply：把本波资源的期望内容写入实际状态
                try {
                    applyWave(env, wave, byId);
                } catch (RuntimeException e) {
                    state = SyncState.FAILED;
                    reason = FailureReason.VALIDATION_FAILED;
                    failureDetail = "第 " + w + " 波应用失败: " + e.getMessage();
                    waveRecords.add(new WaveRecord(w, wave, WaveState.FAILED, failureDetail));
                    appendSkippedWaves(waves, waveRecords, w);
                    break;
                }
                waveRecords.add(new WaveRecord(w, wave, WaveState.APPLIED,
                        "本波资源已写入实际状态: " + wave));

                // 3) 健康门禁：不过则中断，后续波次 SKIPPED
                try {
                    healthChecker.check(env, wave, healthTimeoutMillis);
                } catch (SyncException e) {
                    state = SyncState.FAILED;
                    reason = e.getReason();
                    failureDetail = "第 " + w + " 波 " + e.getMessage();
                    waveRecords.set(waveRecords.size() - 1,
                            new WaveRecord(w, wave, WaveState.FAILED, failureDetail));
                    appendSkippedWaves(waves, waveRecords, w);
                    break;
                }
                waveRecords.set(waveRecords.size() - 1,
                        new WaveRecord(w, wave, WaveState.HEALTH_PASSED,
                                "本波资源已应用并通过健康检查: " + wave));
            }
        } finally {
            stateStore.releaseSyncLock(env, executionId);
        }
        long finishedAt = System.currentTimeMillis();
        SyncExecution execution = new SyncExecution(executionId, env, state, reason,
                failureDetail, List.copyOf(waveRecords), startedAt, finishedAt);
        persistExecution(env, execution);
        if (state == SyncState.SUCCEEDED) {
            log.info("同步成功 env={} executionId={} 波次数={}",
                    env, executionId, waveRecords.size());
        } else {
            log.warn("同步失败（已停止，保留已应用波次）env={} executionId={} reason={} detail={}",
                    env, executionId, reason, failureDetail);
        }
        return execution;
    }

    private static void appendSkippedWaves(List<List<String>> waves,
                                           List<WaveRecord> records,
                                           int failedWaveIndex) {
        for (int k = failedWaveIndex + 1; k < waves.size(); k++) {
            records.add(new WaveRecord(k, waves.get(k), WaveState.SKIPPED,
                    "因第 " + failedWaveIndex + " 波失败而跳过，本波未应用"));
        }
    }

    /**
     * 模拟 apply：以本波资源的期望版本/内容更新 observed.jsonl，
     * 本波之外的观测行原样保留（含坏数据行，apply 不“顺手修复”坏数据）。
     */
    private void applyWave(String env, List<String> wave,
                           Map<String, ResourceDescriptor> byId) {
        Set<String> waveSet = new HashSet<>(wave);
        List<String> keptLines = new ArrayList<>();
        Set<String> replacedIds = new HashSet<>();
        String raw = stateStore.readObserved(env);
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String id = extractIdLenient(trimmed);
            if (id != null && waveSet.contains(id)) {
                replacedIds.add(id);
                continue; // 用期望内容替换
            }
            keptLines.add(trimmed);
        }
        List<String> out = new ArrayList<>(keptLines);
        for (String id : wave) {
            ResourceDescriptor d = byId.get(id);
            out.add(toObservedLine(d));
        }
        stateStore.writeObserved(env, String.join("\n", out) + "\n");
    }

    /** 只在对象是合法 JSON 且 id 为字符串时提取，坏行原样保留。 */
    private static String extractIdLenient(String line) {
        try {
            JsonNode node = ReconcileService.MAPPER.readTree(line);
            if (node != null && node.isObject()) {
                JsonNode id = node.get("id");
                if (id != null && id.isTextual()) {
                    return id.asText();
                }
            }
        } catch (Exception ignored) {
            // 坏数据行不属于本波资源，保留
        }
        return null;
    }

    private static String toObservedLine(ResourceDescriptor d) {
        Map<String, String> obj = new LinkedHashMap<>();
        obj.put("id", d.id());
        if (d.type() != null) {
            obj.put("type", d.type());
        }
        if (d.version() != null) {
            obj.put("version", d.version());
        }
        if (d.desiredValue() != null) {
            obj.put("observedValue", d.desiredValue());
        }
        try {
            return ReconcileService.JSONL_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "资源无法序列化为观测: " + d.id());
        }
    }

    private void persistExecution(String env, SyncExecution execution) {
        try {
            stateStore.saveSyncExecution(env, execution.executionId(),
                    ReconcileService.MAPPER.writeValueAsString(execution));
        } catch (Exception e) {
            log.error("同步执行记录持久化失败 env={} executionId={}", env,
                    execution.executionId(), e);
        }
    }
}
