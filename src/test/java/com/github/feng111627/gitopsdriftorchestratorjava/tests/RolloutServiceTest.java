package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.ItemStatus;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.model.RolloutPhase;
import com.github.feng111627.gitopsdriftorchestratorjava.model.RolloutRecord;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.rollout.RolloutService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务三：渐进发布与回滚。覆盖按比例放量、100% 完成切换、回滚对齐历史快照、
 * 重复回滚/回退放量/对已回滚版本再操作被拒绝。
 */
@DisplayName("业务三-渐进发布：比例放量/回滚对齐/重复拒绝")
class RolloutServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutServiceTest.class);

    private Path root;
    private FileStateStore store;
    private ReconcileService reconcileService;
    private RolloutService rolloutService;

    private static final String DESIRED = """
            [
              {"id":"svc-a","type":"service","version":"v1","desiredValue":"v1@100"}
            ]
            """;

    @BeforeEach
    void setUp() {
        root = TestStateFixtures.newDataRoot();
        store = TestStateFixtures.store(root);
        reconcileService = new ReconcileService(store);
        rolloutService = new RolloutService(store, reconcileService);
        TestStateFixtures.writeDesired(root, "prod", DESIRED);
        TestStateFixtures.writeObserved(root, "prod", List.of(
                TestStateFixtures.observedLine("svc-a", "service", "v1", "v1@100")));
    }

    @Test
    @DisplayName("用例3.1 按比例放量 10->50->100：在途 DRIFTED，100% 完成后重新 SYNCED")
    void progressiveRolloutThenComplete() {
        ReconcileReport before = reconcileService.reconcile("prod");
        assertThat(before.clean()).isTrue();

        RolloutRecord started = rolloutService.start("prod", "svc-a", "v2", 10);
        log.info("[发布-放量] start: {}", started.detail());
        assertThat(started.phase()).isEqualTo(RolloutPhase.IN_PROGRESS);
        assertThat(started.history()).containsExactly(10);
        assertThat(reconcileService.reconcile("prod").items().get(0).status())
                .isEqualTo(ItemStatus.DRIFTED);

        RolloutRecord mid = rolloutService.promote("prod", "svc-a", 50);
        assertThat(mid.percentage()).isEqualTo(50);
        assertThat(mid.history()).containsExactly(10, 50);
        assertThat(reconcileService.reconcile("prod").items().get(0).status())
                .isEqualTo(ItemStatus.DRIFTED);

        RolloutRecord done = rolloutService.promote("prod", "svc-a", 100);
        log.info("[发布-放量] complete: {}", done.detail());
        assertThat(done.phase()).isEqualTo(RolloutPhase.COMPLETED);
        assertThat(done.history()).containsExactly(10, 50, 100);
        assertThat(reconcileService.reconcile("prod").clean()).isTrue();
    }

    @Test
    @DisplayName("用例3.2 放量百分比必须严格递增，重复/回退触发被拒绝且无变更")
    void nonIncreasingPromotionRejected() {
        rolloutService.start("prod", "svc-a", "v2", 10);
        rolloutService.promote("prod", "svc-a", 50);

        assertThatThrownBy(() -> rolloutService.promote("prod", "svc-a", 50))
                .isInstanceOf(SyncException.class)
                .satisfies(e -> log.warn("[发布-拒绝] 重复放量50%被拒: {}",
                        ((SyncException) e).getMessage()));
        assertThatThrownBy(() -> rolloutService.promote("prod", "svc-a", 20))
                .isInstanceOf(SyncException.class);

        // 记录未被污染，仍停留在 50% IN_PROGRESS
        String raw = store.readRollout("prod", "svc-a");
        assertThat(raw).contains("\"percentage\" : 50").contains("\"IN_PROGRESS\"");
    }

    @Test
    @DisplayName("用例3.3 回滚到稳定版本，且回滚后对账结论与发布前历史快照逐项对得上")
    void rollbackMatchesHistoricalSnapshot() {
        // 发布前留一份对账快照（这是“历史上的一次对账结论”）
        var snapshot = reconcileService.reconcileAndSnapshot("prod");
        log.info("[发布-回滚] 发布前快照={}", snapshot.snapshotId());
        assertThat(snapshot.report().clean()).isTrue();

        rolloutService.start("prod", "svc-a", "v2", 10);
        rolloutService.promote("prod", "svc-a", 50);
        assertThat(reconcileService.reconcile("prod").clean()).isFalse();

        RolloutRecord rolledBack = rolloutService.rollback("prod", "svc-a",
                snapshot.snapshotId());
        log.info("[发布-回滚] 结果: {}", rolledBack.detail());
        assertThat(rolledBack.phase()).isEqualTo(RolloutPhase.ROLLED_BACK);
        assertThat(rolledBack.rollbackSnapshot()).isEqualTo(snapshot.snapshotId());
        assertThat(reconcileService.reconcile("prod").clean()).isTrue();
    }

    @Test
    @DisplayName("用例3.4 已回滚的版本重复回滚/再放量均被拒绝，不产生第二次变更")
    void duplicateRollbackAndReoperateRejected() {
        var snapshot = reconcileService.reconcileAndSnapshot("prod");
        rolloutService.start("prod", "svc-a", "v2", 10);
        RolloutRecord first = rolloutService.rollback("prod", "svc-a",
                snapshot.snapshotId());
        assertThat(first.phase()).isEqualTo(RolloutPhase.ROLLED_BACK);

        String observedAfterFirstRollback = TestStateFixtures.readObserved(root, "prod");

        assertThatThrownBy(() -> rolloutService.rollback("prod", "svc-a",
                snapshot.snapshotId()))
                .isInstanceOf(SyncException.class)
                .satisfies(e -> log.warn("[发布-拒绝] 重复回滚被拒: {}",
                        ((SyncException) e).getMessage()));
        assertThatThrownBy(() -> rolloutService.promote("prod", "svc-a", 50))
                .isInstanceOf(SyncException.class)
                .satisfies(e -> log.warn("[发布-拒绝] 回滚后再放量被拒: {}",
                        ((SyncException) e).getMessage()));
        assertThatThrownBy(() -> rolloutService.start("prod", "svc-a", "v2", 10))
                .isInstanceOf(SyncException.class);

        // 实际状态字节级不变，证明没有产生第二次变更
        assertThat(TestStateFixtures.readObserved(root, "prod"))
                .isEqualTo(observedAfterFirstRollback);
    }

    @Test
    @DisplayName("用例3.5 已 100% 完成的发布不能回滚（新版本已是稳定事实）")
    void completedRolloutCannotRollback() {
        var snapshot = reconcileService.reconcileAndSnapshot("prod");
        rolloutService.start("prod", "svc-a", "v2", 10);
        rolloutService.promote("prod", "svc-a", 100);

        assertThatThrownBy(() -> rolloutService.rollback("prod", "svc-a",
                snapshot.snapshotId()))
                .isInstanceOf(SyncException.class)
                .satisfies(e -> log.warn("[发布-拒绝] 完成后回滚被拒: {}",
                        ((SyncException) e).getMessage()));
    }
}
