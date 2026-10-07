package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.release.RollbackException;
import com.github.feng111627.gitopsdriftorchestratorjava.release.RolloutService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.feng111627.gitopsdriftorchestratorjava.tests.support.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 业务：带版本变更的渐进放量、异常回滚、回滚幂等与历史对账对齐。 */
class RolloutServiceTests {

    private FileStateStore store;
    private Reconciler reconciler;
    private RolloutService rollout;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new FileStateStore(dir);
        reconciler = new Reconciler(store);
        rollout = new RolloutService(store, reconciler);
    }

    private DesiredResource apiV1() {
        return desired("service", "api", "v1", spec("image", "api:v1", "replicas", 3));
    }

    private DesiredResource apiV2() {
        return desired("service", "api", "v2", spec("image", "api:v2", "replicas", 3));
    }

    private void seedStableV1() {
        store.saveDesired("prod", List.of(apiV1()));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("image", "api:v1", "replicas", 3)))));
    }

    @Test
    @DisplayName("渐进放量：10% 起步为 ROLLING_OUT，推进到 100% 变 STABLE")
    void canaryThenFull() {
        seedStableV1();

        ReleaseRecord started = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        assertEquals(ReleaseState.ROLLING_OUT, started.state());
        assertEquals(10, started.percentage());
        assertEquals("v1", started.previousVersion());

        ReleaseRecord half = rollout.advance("prod", started.releaseId(), 50);
        assertEquals(ReleaseState.ROLLING_OUT, half.state());

        ReleaseRecord full = rollout.advance("prod", started.releaseId(), 100);
        assertEquals(ReleaseState.STABLE, full.state());
    }

    @Test
    @DisplayName("放量比例只能增加：回退比例被拒绝")
    void percentageCannotGoBack() {
        seedStableV1();
        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 50);
        assertThrows(RollbackException.class, () -> rollout.advance("prod", r.releaseId(), 20));
    }

    @Test
    @DisplayName("异常回滚：恢复到发布前快照，回滚后对账报告与发布前历史报告 id 对得上")
    void rollbackRestoresAndMatchesHistoricalReport() {
        seedStableV1();
        // 发布前留一份对账结论（稳定现场）
        ReconcileReport before = reconciler.reconcile("prod");
        assertTrue(before.converged());

        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        // 开始放量只把 v2 写入期望，观测现场仍停留在 v1（模拟新版本尚未铺开）
        assertEquals(ItemStatus.DRIFTED,
                reconciler.reconcile("prod").items().get(0).status(),
                "放量中新旧期望/现场之间应出现漂移");

        ReconcileReport afterRollback = rollout.rollback("prod", r.releaseId());

        assertEquals(afterRollback.reportId(), before.reportId(),
                "回滚后对账结论必须能和发布前某一次历史对账对得上");
        assertEquals(afterRollback.items(), before.items());
        // 观测现场恢复为 v1 快照
        assertEquals("v1", store.loadObservedRaw("prod").get(0).get("version"));
        ReleaseRecord stored = store.listReleaseRecords("prod").get(0);
        assertEquals(ReleaseState.ROLLED_BACK, stored.state());
        assertEquals(before.reportId(), stored.rollbackBaselineReportId());
    }

    @Test
    @DisplayName("重复回滚被拒绝：第二次回滚不产生任何状态变更")
    void duplicateRollbackRejected() {
        seedStableV1();
        ReconcileReport before = reconciler.reconcile("prod");
        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v2", spec("image", "api:v2", "replicas", 3)))));

        ReconcileReport first = rollout.rollback("prod", r.releaseId());
        assertEquals(before.reportId(), first.reportId());

        RollbackException ex = assertThrows(RollbackException.class,
                () -> rollout.rollback("prod", r.releaseId()));
        assertTrue(ex.getMessage().contains("已经回滚过"));

        // 现场与基线对账没有再变化，只保留一次回滚结论
        assertEquals(before.reportId(), reconciler.reconcile("prod").reportId());
        long rolledBack = store.listReleaseRecords("prod").stream()
                .filter(x -> x.state() == ReleaseState.ROLLED_BACK).count();
        assertEquals(1, rolledBack, "不允许产生重复变更记录");
    }

    @Test
    @DisplayName("已回滚的版本不能再放量：同资源重新发布 v2 被拒绝")
    void rerollingRolledBackVersionRejected() {
        seedStableV1();
        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        rollout.rollback("prod", r.releaseId());

        RollbackException ex = assertThrows(RollbackException.class,
                () -> rollout.startRollout("prod", id("service", "api"),
                        "v2", List.of(apiV2()), 10));
        assertTrue(ex.getMessage().contains("已经回滚过"));
    }

    @Test
    @DisplayName("已回滚的发布不能继续推进放量")
    void advanceAfterRollbackRejected() {
        seedStableV1();
        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        rollout.rollback("prod", r.releaseId());
        assertThrows(RollbackException.class,
                () -> rollout.advance("prod", r.releaseId(), 50));
    }

    @Test
    @DisplayName("全量稳定的版本按约定不允许回滚（回滚只服务于放量中的异常）")
    void stableReleaseCannotRollback() {
        seedStableV1();
        ReleaseRecord r = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        rollout.advance("prod", r.releaseId(), 100);
        assertThrows(RollbackException.class, () -> rollout.rollback("prod", r.releaseId()));
    }

    @Test
    @DisplayName("回滚不同环境互不干扰：test 环境回滚不影响 prod 的现场")
    void rollbackScopedByEnvironment() {
        seedStableV1();
        store.saveDesired("test", List.of(apiV1()));
        store.saveObservedRaw("test", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("image", "api:v1", "replicas", 3)))));

        ReleaseRecord prodRel = rollout.startRollout("prod", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        ReleaseRecord testRel = rollout.startRollout("test", id("service", "api"),
                "v2", List.of(apiV2()), 10);
        rollout.rollback("prod", prodRel.releaseId());

        assertEquals(ReleaseState.ROLLING_OUT,
                store.listReleaseRecords("test").stream()
                        .filter(x -> x.releaseId().equals(testRel.releaseId())).findFirst().orElseThrow()
                        .state());
    }
}
