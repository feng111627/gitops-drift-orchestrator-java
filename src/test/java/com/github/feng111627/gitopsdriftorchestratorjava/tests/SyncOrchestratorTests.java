package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.feng111627.gitopsdriftorchestratorjava.tests.support.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 业务：按波次同步——依赖顺序、健康门禁、中途失败保留现场、并发触发拒绝。 */
class SyncOrchestratorTests {

    private FileStateStore store;
    private Reconciler reconciler;
    private ConfigurableHealthChecker healthChecker;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new FileStateStore(dir);
        reconciler = new Reconciler(store);
        healthChecker = new ConfigurableHealthChecker();
    }

    private SyncOrchestrator orchestrator(long timeoutMillis) {
        return new SyncOrchestrator(store, reconciler, healthChecker, timeoutMillis);
    }

    private DesiredResource database() {
        return desired("stateful", "db", "v1", spec("disk", "10Gi"));
    }

    private DesiredResource api(ResourceId dep) {
        return desired("service", "api", "v1", spec("replicas", 2), dep);
    }

    @Test
    @DisplayName("正常同步：依赖分层为 2 波，全部成功后对账收敛")
    void waveOrderHappyPath() {
        store.saveDesired("dev", List.of(api(database().id()), database()));

        SyncRecord rec = orchestrator(500).sync("dev");

        assertEquals(SyncState.SUCCEEDED, rec.state());
        assertEquals(2, rec.totalWaves());
        assertEquals(2, rec.completedWaves());
        assertTrue(reconciler.reconcile("dev").converged());
        // 无依赖的 db 先应用，api 后应用
        assertEquals(id("stateful", "db"), rec.appliedResources().get(0));
        assertEquals(id("service", "api"), rec.appliedResources().get(1));
    }

    @Test
    @DisplayName("校验失败：依赖指向不存在的资源，同步被拒且不应用任何东西")
    void validationFailureUnknownDependency() {
        store.saveDesired("dev", List.of(
                api(id("stateful", "ghost"))));

        SyncRecord rec = orchestrator(500).sync("dev");

        assertEquals(SyncState.FAILED, rec.state());
        assertEquals(FailureReason.VALIDATION_FAILED, rec.failureReason());
        assertEquals(0, rec.completedWaves());
        assertTrue(rec.appliedResources().isEmpty(), "校验阶段不得应用任何资源");
        assertTrue(store.loadObservedRaw("dev").isEmpty(), "观测状态不得被改动");
    }

    @Test
    @DisplayName("校验失败：依赖成环被检出，归类 VALIDATION_FAILED")
    void validationFailureCycle() {
        DesiredResource a = desired("service", "a", "v1", spec("x", 1), id("service", "b"));
        DesiredResource b = desired("service", "b", "v1", spec("x", 1), id("service", "a"));
        store.saveDesired("dev", List.of(a, b));

        SyncRecord rec = orchestrator(500).sync("dev");

        assertEquals(FailureReason.VALIDATION_FAILED, rec.failureReason());
        assertTrue(rec.failureDetail().contains("成环"), rec.failureDetail());
    }

    @Test
    @DisplayName("健康检查超时：第 1 波资源不健康则停在第 0 波后，第 2 波绝不开始，已应用部分保留")
    void healthTimeoutStopsLaterWaves() {
        healthChecker.markNeverHealthy(id("stateful", "db"));
        store.saveDesired("dev", List.of(api(database().id()), database()));

        SyncRecord rec = orchestrator(50).sync("dev");

        assertEquals(SyncState.FAILED, rec.state());
        assertEquals(FailureReason.HEALTH_CHECK_TIMEOUT, rec.failureReason());
        assertEquals(0, rec.completedWaves(), "第 1 波未通过，完成波次仍为 0");
        assertEquals(List.of(id("stateful", "db")), rec.appliedResources(),
                "失败资源已应用并保留现场");
        assertTrue(store.loadObservedRaw("dev").stream()
                        .anyMatch(m -> "db".equals(m.get("name"))),
                "已应用的 db 必须留在观测状态中");
        assertTrue(store.loadObservedRaw("dev").stream()
                        .noneMatch(m -> "api".equals(m.get("name"))),
                "第 2 波的 api 不允许被应用");
    }

    @Test
    @DisplayName("依赖没就绪：健康检查报告上游阻塞，归类 DEPENDENCY_NOT_READY 并中断")
    void dependencyNotReady() {
        healthChecker.markDependencyBlocked(id("stateful", "db"), id("stateful", "storage"));
        store.saveDesired("dev", List.of(database()));

        SyncRecord rec = orchestrator(500).sync("dev");

        assertEquals(FailureReason.DEPENDENCY_NOT_READY, rec.failureReason());
        assertTrue(rec.failureDetail().contains("storage"));
        assertEquals(List.of(id("stateful", "db")), rec.appliedResources(), "应用动作已发生并保留");
    }

    @Test
    @DisplayName("中途失败后再触发同步：修好故障后可从干净结论继续，最终成功收敛")
    void retryAfterFailureConverges() {
        healthChecker.markNeverHealthy(id("stateful", "db"));
        store.saveDesired("dev", List.of(api(database().id()), database()));

        SyncRecord failed = orchestrator(30).sync("dev");
        assertEquals(FailureReason.HEALTH_CHECK_TIMEOUT, failed.failureReason());

        healthChecker.clearFaults();
        SyncRecord retry = orchestrator(300).sync("dev");
        assertEquals(SyncState.SUCCEEDED, retry.state());
        assertTrue(reconciler.reconcile("dev").converged());
    }

    @Test
    @DisplayName("并发触发：同一环境两份同步同时进来，恰好一份成功一份被 REJECTED，无交叉执行")
    void concurrentTriggerOneRejected() throws Exception {
        // 让一次健康检查耗时足够长，保证两个线程并发竞争
        CountDownLatch enteredHealth = new CountDownLatch(1);
        CountDownLatch releaseHealth = new CountDownLatch(1);
        HealthChecker blocking = (resource, timeout) -> {
            enteredHealth.countDown();
            assertTrue(releaseHealth.await(5, TimeUnit.SECONDS));
            return null;
        };
        SyncOrchestrator orch = new SyncOrchestrator(store, reconciler, blocking, 5000);
        store.saveDesired("dev", List.of(database()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SyncRecord> f1 = pool.submit(() -> orch.sync("dev"));
            assertTrue(enteredHealth.await(5, TimeUnit.SECONDS), "第一个同步应已进入执行");
            AtomicInteger rejectedSeen = new AtomicInteger();
            Future<SyncRecord> f2 = pool.submit(() -> {
                try {
                    return orch.sync("dev");
                } catch (ConcurrentSyncException e) {
                    rejectedSeen.incrementAndGet();
                    throw e;
                }
            });

            assertThrows(Exception.class, () -> f2.get(5, TimeUnit.SECONDS),
                    "第二次并发触发必须被拒绝");
            releaseHealth.countDown();
            SyncRecord first = f1.get(5, TimeUnit.SECONDS);
            assertEquals(SyncState.SUCCEEDED, first.state());
            assertEquals(1, rejectedSeen.get());
        } finally {
            releaseHealth.countDown();
            pool.shutdownNow();
        }

        long rejected = store.listSyncRecords("dev").stream()
                .filter(r -> r.state() == SyncState.REJECTED).count();
        assertEquals(1, rejected, "必须留下一条 REJECTED 记录说清被拒绝的那次触发");
    }

    @Test
    @DisplayName("不同环境互不阻塞：dev 在同步时，test 环境的同步可以正常执行")
    void differentEnvironmentsDoNotBlock() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HealthChecker blocking = (resource, timeout) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        };
        SyncOrchestrator orch = new SyncOrchestrator(store, reconciler, blocking, 5000);
        store.saveDesired("dev", List.of(database()));
        store.saveDesired("test", List.of(
                desired("service", "web", "v1", spec("port", 8080))));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = pool.submit(() -> orch.sync("dev"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // dev 还在跑，test 用独立线程/编排调用不受影响：这里直接验证锁是按环境隔离的
            SyncOrchestrator fastOrch = new SyncOrchestrator(store, reconciler,
                    new DefaultHealthChecker(), 500);
            SyncRecord testRec = fastOrch.sync("test");
            assertEquals(SyncState.SUCCEEDED, testRec.state());
            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail(e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
