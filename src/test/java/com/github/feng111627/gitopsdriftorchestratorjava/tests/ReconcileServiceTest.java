package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.ItemStatus;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileItem;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务一：对账。覆盖全部一致、漂移/缺失/多余、坏数据单列可定位、重复执行结论稳定。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("业务一-对账：逐项结论与稳定性")
class ReconcileServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReconcileServiceTest.class);

    private Path root;
    private FileStateStore store;
    private ReconcileService reconcileService;

    @BeforeEach
    void setUp() {
        root = TestStateFixtures.newDataRoot();
        store = TestStateFixtures.store(root);
        reconcileService = new ReconcileService(store);
    }

    private Map<String, ReconcileItem> byId(ReconcileReport r) {
        return r.items().stream()
                .filter(i -> i.resourceId() != null)
                .collect(Collectors.toMap(ReconcileItem::resourceId, i -> i, (a, b) -> a,
                        java.util.TreeMap::new));
    }

    @Test
    @DisplayName("用例1.1 期望与实际完全一致：全部 SYNCED、clean=true")
    void allSynced() {
        TestStateFixtures.writeDesired(root, "dev", TestStateFixtures.desiredThreeResources());
        TestStateFixtures.writeObserved(root, "dev", List.of(
                TestStateFixtures.observedLine("svc-a", "service", "v1", "a@100"),
                TestStateFixtures.observedLine("svc-b", "service", "v1", "b@100"),
                TestStateFixtures.observedLine("svc-c", "config", "v1", "c@100")));

        ReconcileReport report = reconcileService.reconcile("dev");

        log.info("[对账-一致] synced={} drifted={} missing={} extra={} invalid={} clean={}",
                report.syncedCount(), report.driftedCount(), report.missingCount(),
                report.extraCount(), report.invalidCount(), report.clean());
        assertThat(report.syncedCount()).isEqualTo(3);
        assertThat(report.clean()).isTrue();
        assertThat(report.items()).allSatisfy(i ->
                assertThat(i.status()).isEqualTo(ItemStatus.SYNCED));
    }

    @Test
    @DisplayName("用例1.2 漂移+缺失+多余并存，逐项状态与计数正确")
    void driftedMissingExtra() {
        TestStateFixtures.writeDesired(root, "dev", TestStateFixtures.desiredThreeResources());
        TestStateFixtures.writeObserved(root, "dev", List.of(
                // svc-a 内容漂移
                TestStateFixtures.observedLine("svc-a", "service", "v1", "a@90"),
                // svc-b 版本漂移
                TestStateFixtures.observedLine("svc-b", "service", "v2", "b@100"),
                // svc-c 缺失（不出现）
                // svc-x 多余
                TestStateFixtures.observedLine("svc-x", "service", "v9", "x@100")));

        ReconcileReport report = reconcileService.reconcile("dev");
        log.info("[对账-漂移] 结果={}", report.items().stream()
                .map(i -> i.resourceId() + ":" + i.status()).toList());

        Map<String, ReconcileItem> items = byId(report);
        assertThat(items.get("svc-a").status()).isEqualTo(ItemStatus.DRIFTED);
        assertThat(items.get("svc-a").detail()).contains("内容不一致");
        assertThat(items.get("svc-b").status()).isEqualTo(ItemStatus.DRIFTED);
        assertThat(items.get("svc-b").detail()).contains("版本不一致");
        assertThat(items.get("svc-c").status()).isEqualTo(ItemStatus.MISSING);
        assertThat(items.get("svc-x").status()).isEqualTo(ItemStatus.EXTRA);
        assertThat(report.driftedCount()).isEqualTo(2);
        assertThat(report.missingCount()).isEqualTo(1);
        assertThat(report.extraCount()).isEqualTo(1);
        assertThat(report.clean()).isFalse();
    }

    @Test
    @DisplayName("用例1.3 坏数据不跳过：字段缺失/类型错误/JSON损坏分别标 INVALID 且可定位")
    void invalidEntriesAreReported() {
        TestStateFixtures.writeDesired(root, "dev", TestStateFixtures.desiredThreeResources());
        TestStateFixtures.writeObservedRaw(root, "dev", String.join("\n",
                TestStateFixtures.observedLine("svc-a", "service", "v1", "a@100"),
                "{\"id\":\"svc-b\",\"type\":\"service\",\"version\":\"v1\"}", // 缺 observedValue 允许；svc-b 应判漂移/一致按内容
                "{\"id\":123,\"type\":\"service\"}",                       // id 类型错误
                "{not-json",                                                 // JSON 损坏
                "{\"type\":\"service\",\"version\":\"v1\"}",               // 缺 id
                "{\"id\":\"svc-c\",\"type\":\"config\",\"version\":7,\"observedValue\":\"c@100\"}" // version 类型错误
        ) + "\n");

        ReconcileReport report = reconcileService.reconcile("dev");
        log.info("[对账-坏数据] INVALID 条目={}", report.items().stream()
                .filter(i -> i.status() == ItemStatus.INVALID)
                .map(i -> i.location() + " => " + i.detail()).toList());

        List<ReconcileItem> invalids = report.items().stream()
                .filter(i -> i.status() == ItemStatus.INVALID).toList();
        // 坏行：L3 id 类型错、L4 JSON 损坏、L5 缺 id、L6 version 类型错
        assertThat(invalids).hasSize(4);
        assertThat(invalids).anySatisfy(i -> {
            assertThat(i.detail()).contains("id").contains("number");
            assertThat(i.location()).contains("L3");
        });
        assertThat(invalids).anySatisfy(i -> {
            assertThat(i.detail()).contains("JSON 格式损坏");
            assertThat(i.location()).contains("L4");
        });
        assertThat(invalids).anySatisfy(i -> {
            assertThat(i.detail()).contains("缺少字段: id");
            assertThat(i.location()).contains("L5");
        });
        assertThat(invalids).anySatisfy(i -> {
            assertThat(i.detail()).contains("字段 version").contains("number");
            assertThat(i.location()).contains("L6");
        });
        // 好数据依旧正常对账：svc-a 一致
        assertThat(byId(report).get("svc-a").status()).isEqualTo(ItemStatus.SYNCED);
        assertThat(report.invalidCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("用例1.4 重复 id 也标记为 INVALID，并注明冲突行")
    void duplicateIdsInvalid() {
        TestStateFixtures.writeDesired(root, "dev", TestStateFixtures.desiredThreeResources());
        TestStateFixtures.writeObserved(root, "dev", List.of(
                TestStateFixtures.observedLine("svc-a", "service", "v1", "a@100"),
                TestStateFixtures.observedLine("svc-a", "service", "v1", "a@100"),
                TestStateFixtures.observedLine("svc-b", "service", "v1", "b@100"),
                TestStateFixtures.observedLine("svc-c", "config", "v1", "c@100")));

        ReconcileReport report = reconcileService.reconcile("dev");
        List<ReconcileItem> dup = report.items().stream()
                .filter(i -> "svc-a".equals(i.resourceId())).toList();
        log.info("[对账-重复id] svc-a 条目数={} 状态={}", dup.size(),
                dup.stream().map(ReconcileItem::status).toList());
        assertThat(dup).isNotEmpty();
        assertThat(dup).allSatisfy(i -> assertThat(i.status()).isEqualTo(ItemStatus.INVALID));
        assertThat(dup.get(0).detail()).contains("重复");
    }

    @Test
    @DisplayName("用例1.5 同一份输入连续对账三次，逐项结论与顺序完全一致（确定性）")
    void repeatableAcrossRuns() {
        TestStateFixtures.writeDesired(root, "dev", TestStateFixtures.desiredThreeResources());
        TestStateFixtures.writeObservedRaw(root, "dev", String.join("\n",
                TestStateFixtures.observedLine("svc-b", "service", "v2", "b@100"),
                "{not-json",
                TestStateFixtures.observedLine("svc-x", "service", "v9", "x@100")) + "\n");

        ReconcileReport first = reconcileService.reconcile("dev");
        ReconcileReport second = reconcileService.reconcile("dev");
        ReconcileService.ReconcileResult snap = reconcileService.reconcileAndSnapshot("dev");

        log.info("[对账-稳定性] 三次报告条目指纹={}",
                first.items().stream().map(i -> i.resourceId() + ":" + i.status()).toList());
        assertThat(second.items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.items());
        assertThat(snap.report().items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.items());
        assertThat(snap.snapshotId()).startsWith("snap-");
    }
}
