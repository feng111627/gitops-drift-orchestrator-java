package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
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

/** 业务：对账——逐项结论、坏条目定位、重复执行结论稳定。 */
class ReconcilerTests {

    private FileStateStore store;
    private Reconciler reconciler;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new FileStateStore(dir);
        reconciler = new Reconciler(store);
    }

    @Test
    @DisplayName("正常对账：期望与实际完全一致，全部 SYNCED 且报告收敛")
    void allSynced() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2, "image", "api:v1")),
                desired("config", "cfg", "v1", spec("timeout", 30))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("replicas", 2, "image", "api:v1")),
                observed("config", "cfg", "v1", spec("timeout", 30)))));

        ReconcileReport report = reconciler.reconcile("prod");

        assertEquals(2, report.count(ItemStatus.SYNCED));
        assertTrue(report.converged());
    }

    @Test
    @DisplayName("漂移：字段值不一致标 DRIFTED，detail 指出具体字段")
    void driftFieldValue() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("replicas", 3)))));

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);

        assertEquals(ItemStatus.DRIFTED, item.status());
        assertTrue(item.detail().contains("replicas"), "差异说明应包含字段名: " + item.detail());
    }

    @Test
    @DisplayName("漂移：版本号不一致标 DRIFTED 并说明期望/实际版本")
    void driftVersion() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v2", spec("replicas", 2))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("replicas", 2)))));

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);

        assertEquals(ItemStatus.DRIFTED, item.status());
        assertTrue(item.detail().contains("v2") && item.detail().contains("v1"));
    }

    @Test
    @DisplayName("缺失：期望有、实际没有标 MISSING")
    void missing() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2))));
        store.saveObservedRaw("prod", new ArrayList<>());

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);
        assertEquals(ItemStatus.MISSING, item.status());
    }

    @Test
    @DisplayName("多余：实际多出的资源标 EXTRA，不影响其他条目")
    void extra() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("replicas", 2)),
                observed("service", "ghost", "v9", spec("x", 1)))));

        ReconcileReport report = reconciler.reconcile("prod");
        assertEquals(1, report.count(ItemStatus.SYNCED));
        ReconcileItem extra = report.items().stream()
                .filter(i -> i.status() == ItemStatus.EXTRA).findFirst().orElseThrow();
        assertEquals(id("service", "ghost"), extra.id());
    }

    @Test
    @DisplayName("坏条目-缺字段：观测缺少 name，标 INVALID 并说清第几项缺什么")
    void invalidMissingName() {
        store.saveDesired("prod", List.of());
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                Map.of("type", "service", "spec", Map.of("a", 1)))));

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);
        assertEquals(ItemStatus.INVALID, item.status());
        assertTrue(item.detail().contains("第 1 条") && item.detail().contains("name"));
    }

    @Test
    @DisplayName("坏条目-类型错：spec 是字符串而不是对象，标 INVALID 并指出实际类型")
    void invalidSpecWrongType() {
        store.saveDesired("prod", List.of());
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                Map.of("type", "service", "name", "api", "version", "v1", "spec", "not-a-map"))));

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);
        assertEquals(ItemStatus.INVALID, item.status());
        assertTrue(item.detail().contains("spec"), item.detail());
    }

    @Test
    @DisplayName("坏条目-类型错：version 是数字，标 INVALID 而不是跳过")
    void invalidVersionWrongType() {
        store.saveDesired("prod", List.of());
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                Map.of("type", "service", "name", "api", "version", 123,
                        "spec", Map.of("a", 1)))));

        ReconcileItem item = reconciler.reconcile("prod").items().get(0);
        assertEquals(ItemStatus.INVALID, item.status());
        assertTrue(item.detail().contains("version"), item.detail());
    }

    @Test
    @DisplayName("坏条目混在正常数据里：既标 INVALID，又照常给出其他条目的结论")
    void invalidMixedWithGood() {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                Map.of("type", "service", "name", "broken", "spec", "oops"),
                observed("service", "api", "v1", spec("replicas", 2)))));

        ReconcileReport report = reconciler.reconcile("prod");
        assertEquals(1, report.count(ItemStatus.INVALID));
        assertEquals(1, report.count(ItemStatus.SYNCED));
    }

    @Test
    @DisplayName("稳定性：同一份输入重复对账 3 次，reportId、条目状态、首次时间戳全部一致")
    void repeatedReconcileIsStable() throws Exception {
        store.saveDesired("prod", List.of(
                desired("service", "api", "v1", spec("replicas", 2)),
                desired("config", "cfg", "v1", spec("timeout", 30))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "api", "v1", spec("replicas", 2)),
                observed("config", "cfg", "v1", spec("timeout", 99)))));

        ReconcileReport first = reconciler.reconcile("prod");
        Thread.sleep(20);
        ReconcileReport second = reconciler.reconcile("prod");
        Thread.sleep(20);
        ReconcileReport third = reconciler.reconcile("prod");

        assertEquals(first.reportId(), second.reportId());
        assertEquals(second.reportId(), third.reportId());
        assertEquals(first.generatedAt(), second.generatedAt(), "归档后时间戳必须保持首次值");
        assertEquals(first.items(), second.items());
        assertEquals(1, store.listReports("prod").size(), "同内容重复对账只归档一份报告");
    }

    @Test
    @DisplayName("稳定性：坏条目顺序与漂移差异路径在重复执行间保持一致")
    void stableOrderingAcrossRuns() {
        store.saveDesired("prod", List.of(
                desired("service", "zeta", "v1", spec("a", 1)),
                desired("service", "alpha", "v1", spec("a", 1))));
        store.saveObservedRaw("prod", new ArrayList<>(List.of(
                observed("service", "zeta", "v1", spec("a", 2)),
                observed("service", "alpha", "v1", spec("a", 1)))));

        ReconcileReport r1 = reconciler.reconcile("prod");
        ReconcileReport r2 = reconciler.reconcile("prod");

        assertEquals(r1.items(), r2.items());
        assertEquals(id("service", "alpha"), r1.items().get(0).id(), "条目按资源排序");
    }
}
