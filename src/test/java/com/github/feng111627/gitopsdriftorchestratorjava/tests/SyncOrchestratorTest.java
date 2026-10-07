package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncExecution;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncState;
import com.github.feng111627.gitopsdriftorchestratorjava.model.WaveRecord;
import com.github.feng111627.gitopsdriftorchestratorjava.model.WaveState;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.HealthChecker;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.ReconcileBasedHealthChecker;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncException;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务二：同步编排。覆盖波次顺序与健康门禁、依赖未就绪、健康超时中断保留现场、并发互斥。
 */
@DisplayName("业务二-同步：波次/健康门禁/失败中断/并发互斥")
class SyncOrchestratorTest {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestratorTest.class);

    private Path root;
    private FileStateStore store;
    private ReconcileService reconcileService;

    /** app 依赖 db，db 无依赖：db 必须在第 0 波、app 在第 1 波 */
    private static final String DESIRED_WITH_DEPENDENCY = """
            [
              {"id":"app","type":"service","version":"v1","desiredValue":"app@100",
               "dependsOn":["db"]},
              {"id":"db","type":"database","version":"v1","desiredValue":"db@100","dependsOn":[]}
            ]
            """;

    @BeforeEach
    void setUp() {
        root = TestStateFixtures.newDataRoot();
        store = TestStateFixtures.store(root);
        reconcileService = new ReconcileService(store);
    }

    private SyncOrchestrator orchestrator(HealthChecker checker, long timeoutMs) {
        return new SyncOrchestrator(store, checker, reconcileService, timeoutMs);
    }

    private ReconcileBasedHealthChecker realHealthChecker() {
        return new ReconcileBasedHealthChecker(reconcileService, 10);
    }

    @Test
    @DisplayName("用例2.1 依赖决定波次：db 第0波、app 第1波，顺序同步后全部一致")
    void wavesOrderedByDependency() {
        TestStateFixtures.writeDesired(root, "staging", DESIRED_WITH_DEPENDENCY);
        // 实际状态：两个都缺失
        TestStateFixtures.writeObservedRaw(root, "staging", "");

        SyncExecution exec = orchestrator(realHealthChecker(), 500).trigger("staging");

        log.info("[同步-波次] 状态={} 波次={}", exec.state(),
                exec.waves().stream().map(w -> w.wave() + ":" + w.resourceIds()
                        + ":" + w.state()).toList());
        assertThat(exec.state()).isEqualTo(SyncState.SUCCEEDED);
        assertThat(exec.waves()).hasSize(2);
        assertThat(exec.waves().get(0).resourceIds()).containsExactly("db");
        assertThat(exec.waves().get(0).state()).isEqualTo(WaveState.HEALTH_PASSED);
        assertThat(exec.waves().get(1).resourceIds()).containsExactly("app");
        assertThat(exec.waves().get(1).state()).isEqualTo(WaveState.HEALTH_PASSED);
        assertThat(reconcileService.reconcile("staging").clean()).isTrue();
    }

    @Test
    @DisplayName("用例2.2 依赖不存在：变更前直接失败，原因 DEPENDENCY_NOT_READY")
    void missingDependencyFailsBeforeAnyChange() {
        TestStateFixtures.writeDesired(root, "staging", """
                [
                  {"id":"app","type":"service","version":"v1","desiredValue":"app@100",
                   "dependsOn":["ghost"]}
                ]
                """);
        TestStateFixtures.writeObservedRaw(root, "staging", "");

        SyncOrchestrator orch = orchestrator(realHealthChecker(), 500);
        assertThatThrownBy(() -> orch.trigger("staging"))
                .isInstanceOf(SyncException.class)
                .satisfies(e -> assertThat(((SyncException) e).getReason())
                        .isEqualTo(FailureReason.DEPENDENCY_NOT_READY));
        log.info("[同步-依赖缺失] 实际状态保持为空: '{}'",
                TestStateFixtures.readObserved(root, "staging"));
        assertThat(TestStateFixtures.readObserved(root, "staging").trim()).isEmpty();
    }

    @Test
    @DisplayName("用例2.3 健康检查超时：停在失败波，后续波 SKIPPED，已应用波次保留")
    void healthTimeoutStopsAndKeepsAppliedWaves() {
        TestStateFixtures.writeDesired(root, "staging", DESIRED_WITH_DEPENDENCY);
        TestStateFixtures.writeObservedRaw(root, "staging", "");
        // 健康检查永远不过
        HealthChecker alwaysUnhealthy = (env, ids, timeout) -> {
            throw new SyncException(FailureReason.HEALTH_TIMEOUT,
                    "模拟健康检查超时 " + ids);
        };

        SyncExecution exec = orchestrator(alwaysUnhealthy, 50).trigger("staging");

        log.warn("[同步-健康超时] state={} reason={} 波次明细={}",
                exec.state(), exec.failureReason(),
                exec.waves().stream().map(w -> w.wave() + ":" + w.resourceIds()
                        + ":" + w.state()).toList());
        assertThat(exec.state()).isEqualTo(SyncState.FAILED);
        assertThat(exec.failureReason()).isEqualTo(FailureReason.HEALTH_TIMEOUT);
        assertThat(exec.waves().get(0).state()).isEqualTo(WaveState.FAILED);
        assertThat(exec.waves().get(1).state()).isEqualTo(WaveState.SKIPPED);
        // 第 0 波 db 已 apply 并保留；第 1 波 app 未应用
        String observed = TestStateFixtures.readObserved(root, "staging");
        assertThat(observed).contains("\"id\":\"db\"").doesNotContain("\"id\":\"app\"");
    }

    @Test
    @DisplayName("用例2.4 并发触发：同一环境第二个触发被明确拒绝，不出现交叉执行")
    void concurrentTriggerRejected() throws Exception {
        TestStateFixtures.writeDesired(root, "prod", DESIRED_WITH_DEPENDENCY);
        TestStateFixtures.writeObservedRaw(root, "prod", "");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 第一个同步卡在健康检查期间持有锁
        HealthChecker blocking = (env, ids, timeout) -> {
            entered.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return true;
        };
        SyncOrchestrator orch = orchestrator(blocking, 5000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<SyncExecution> first = new AtomicReference<>();
        Future<?> firstFuture = pool.submit(() -> first.set(orch.trigger("prod")));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Object> secondError = new AtomicReference<>();
        Future<?> secondFuture = pool.submit(() -> {
            try {
                orch.trigger("prod");
            } catch (SyncException e) {
                secondError.set(e);
            }
        });
        secondFuture.get(2, TimeUnit.SECONDS);
        release.countDown();
        firstFuture.get(2, TimeUnit.SECONDS);
        pool.shutdownNow();

        log.warn("[同步-并发] 第二触发结果={}",
                secondError.get() == null ? "未拒绝(异常!)"
                        : ((SyncException) secondError.get()).getReason());
        assertThat(secondError.get()).isInstanceOf(SyncException.class);
        assertThat(((SyncException) secondError.get()).getReason())
                .isEqualTo(FailureReason.CONCURRENT_SYNC_REJECTED);
        assertThat(first.get().state()).isEqualTo(SyncState.SUCCEEDED);
    }

    @Test
    @DisplayName("用例2.5 失败后再次触发可重跑，锁已释放，第二次成功")
    void retryAfterFailureSucceeds() {
        TestStateFixtures.writeDesired(root, "staging", DESIRED_WITH_DEPENDENCY);
        TestStateFixtures.writeObservedRaw(root, "staging", "");
        HealthChecker firstUnhealthyThenHealthy = new HealthChecker() {
            private int calls = 0;

            @Override
            public boolean check(String env, List<String> ids, long timeout) {
                if (calls++ == 0) {
                    throw new SyncException(FailureReason.HEALTH_TIMEOUT, "首次失败");
                }
                return true;
            }
        };
        SyncOrchestrator orch = orchestrator(firstUnhealthyThenHealthy, 50);

        SyncExecution failed = orch.trigger("staging");
        log.info("[同步-失败重跑] 第一次 state={} reason={}",
                failed.state(), failed.failureReason());
        assertThat(failed.state()).isEqualTo(SyncState.FAILED);

        SyncExecution retried = orch.trigger("staging");
        log.info("[同步-失败重跑] 第二次 state={} 波次={}", retried.state(),
                retried.waves().stream().map(WaveRecord::state).toList());
        assertThat(retried.state()).isEqualTo(SyncState.SUCCEEDED);
    }
}
