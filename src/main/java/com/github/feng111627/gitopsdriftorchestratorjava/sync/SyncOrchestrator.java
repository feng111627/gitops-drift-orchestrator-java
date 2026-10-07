package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.DependencyPlanner;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ValidationException;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按波次推进的同步编排器。
 *
 * <p>语义：
 * <ul>
 *   <li>同一环境同一时刻最多一份同步：第二个触发立即抛 {@link ConcurrentSyncException}（拒绝，不排队、不交叉）。</li>
 *   <li>先校验期望状态（依赖缺失/成环 → VALIDATION_FAILED，不应用任何东西）。</li>
 *   <li>按波次顺序：对波内每个资源“应用 → 健康检查”；本波全部健康才进下一波。</li>
 *   <li>任一资源失败立即停止：未开始的波绝不动；已应用的资源与已写入的观测状态原样保留。</li>
 *   <li>失败原因归类为 VALIDATION_FAILED / HEALTH_CHECK_TIMEOUT / DEPENDENCY_NOT_READY，
 *       SyncRecord 记录完成到第几波、已应用哪些资源，供事后对账说清现场。</li>
 * </ul>
 */
public class SyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrator.class);

    private final StateStore store;
    private final Reconciler reconciler;
    private final HealthChecker healthChecker;
    private final long healthTimeoutMillis;

    /** 每个环境一把锁，tryLock 失败即拒绝并发触发。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SyncOrchestrator(StateStore store, Reconciler reconciler, HealthChecker healthChecker) {
        this(store, reconciler, healthChecker, 2000);
    }

    public SyncOrchestrator(StateStore store, Reconciler reconciler,
                            HealthChecker healthChecker, long healthTimeoutMillis) {
        this.store = store;
        this.reconciler = reconciler;
        this.healthChecker = healthChecker;
        this.healthTimeoutMillis = healthTimeoutMillis;
    }

    public SyncRecord sync(String environment) {
        ReentrantLock lock = locks.computeIfAbsent(environment, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            String detail = "环境 " + environment + " 已有同步正在执行，拒绝并发触发";
            log.warn(detail);
            SyncRecord rejected = record(environment, Instant.now(), Instant.now(),
                    SyncState.REJECTED, FailureReason.CONCURRENT_SYNC, detail,
                    0, 0, List.of());
            store.saveSyncRecord(rejected);
            throw new ConcurrentSyncException(detail);
        }

        Instant startedAt = Instant.now();
        String runId = "sync-" + UUID.randomUUID();
        List<ResourceId> applied = new ArrayList<>();
        try {
            log.info("[{}] 同步开始 runId={}", environment, runId);
            List<DesiredResource> desired = store.loadDesired(environment);

            // 1. 校验 + 波次规划（失败不碰任何状态）
            List<SyncWave> waves;
            try {
                waves = DependencyPlanner.planWaves(desired);
            } catch (ValidationException e) {
                return finish(environment, startedAt, runId, FailureReason.VALIDATION_FAILED,
                        e.getMessage(), 0, applied, wavesCountSafe(desired));
            }

            Map<ResourceId, DesiredResource> byId = new LinkedHashMap<>();
            for (DesiredResource d : desired) {
                byId.put(d.id(), d);
            }

            int completedWaves = 0;
            for (SyncWave wave : waves) {
                log.info("[{}] 进入第 {} 波，资源 {}", environment, wave.index(), wave.resources());
                for (ResourceId rid : wave.resources()) {
                    DesiredResource resource = byId.get(rid);
                    String unhealthy;
                    try {
                        apply(environment, resource);
                        applied.add(rid);
                        unhealthy = healthChecker.check(resource, healthTimeoutMillis);
                    } catch (DependencyNotReadyException e) {
                        // 应用已发生但依赖没就绪：现场保留，立即停
                        persistObserved(environment);
                        return finish(environment, startedAt, runId,
                                FailureReason.DEPENDENCY_NOT_READY, e.getMessage(),
                                completedWaves, applied, waves.size());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        persistObserved(environment);
                        return finish(environment, startedAt, runId,
                                FailureReason.HEALTH_CHECK_TIMEOUT,
                                "健康检查等待被中断：" + rid, completedWaves, applied, waves.size());
                    }
                    if (unhealthy != null) {
                        persistObserved(environment);
                        return finish(environment, startedAt, runId,
                                FailureReason.HEALTH_CHECK_TIMEOUT, unhealthy,
                                completedWaves, applied, waves.size());
                    }
                    persistObserved(environment);
                    log.info("[{}] 资源 {} 已应用且健康", environment, rid);
                }
                completedWaves++;
                log.info("[{}] 第 {} 波全部通过健康检查", environment, wave.index());
            }

            return finish(environment, startedAt, runId, null, null,
                    completedWaves, applied, waves.size());
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(String environment, DesiredResource resource) {
        // 模拟“应用到实际环境”：把期望条目写进观测状态（保留其他观测条目）。
        List<Map<String, Object>> observed = new ArrayList<>(store.loadObservedRaw(environment));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", resource.id().type());
        entry.put("name", resource.id().name());
        entry.put("version", resource.version());
        entry.put("spec", resource.spec());
        observed.removeIf(m -> {
            Object t = m.get("type");
            Object n = m.get("name");
            return t instanceof String && n instanceof String
                    && t.equals(resource.id().type()) && n.equals(resource.id().name());
        });
        observed.add(entry);
        store.saveObservedRaw(environment, observed);
    }

    private void persistObserved(String environment) {
        // apply 已即时落盘；这里保留显式钩子，强调失败时现场已持久化、不会回滚。
        log.debug("[{}] 已保存应用现场（共 {} 条观测）",
                environment, store.loadObservedRaw(environment).size());
    }

    private int wavesCountSafe(List<DesiredResource> desired) {
        try {
            return DependencyPlanner.planWaves(desired).size();
        } catch (ValidationException e) {
            return 0;
        }
    }

    private SyncRecord finish(String environment, Instant startedAt, String runId,
                              FailureReason reason, String detail, int completedWaves,
                              List<ResourceId> applied, int totalWaves) {
        SyncState state = reason == null ? SyncState.SUCCEEDED : SyncState.FAILED;
        Instant finishedAt = Instant.now();
        SyncRecord rec = new SyncRecord(runId, environment, startedAt, finishedAt, state,
                reason, detail, totalWaves, completedWaves, List.copyOf(applied));
        store.saveSyncRecord(rec);
        if (state == SyncState.SUCCEEDED) {
            log.info("[{}] 同步成功，共 {} 波，已应用 {}", environment, totalWaves, applied);
        } else {
            log.warn("[{}] 同步结束状态={} 原因={} 完成波次={}/{} 已应用={} 详情={}",
                    environment, state, reason, completedWaves, totalWaves, applied, detail);
        }
        // 同步后自动跑一次对账，让“当前现场”随时可与期望对得上
        ReconcileReport report = reconciler.reconcile(environment);
        log.info("[{}] 同步后对账报告 {}：SYNCED={} DRIFTED={} MISSING={} EXTRA={} INVALID={}",
                environment, report.reportId(),
                report.count(ItemStatus.SYNCED), report.count(ItemStatus.DRIFTED),
                report.count(ItemStatus.MISSING), report.count(ItemStatus.EXTRA),
                report.count(ItemStatus.INVALID));
        return rec;
    }

    private static SyncRecord record(String environment, Instant startedAt, Instant finishedAt,
                                     SyncState state, FailureReason reason, String detail,
                                     int totalWaves, int completedWaves, List<ResourceId> applied) {
        return new SyncRecord("sync-" + UUID.randomUUID(), environment, startedAt, finishedAt,
                state, reason, detail, totalWaves, completedWaves, List.copyOf(applied));
    }
}
