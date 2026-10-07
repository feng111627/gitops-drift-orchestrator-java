package com.github.feng111627.gitopsdriftorchestratorjava.release;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 带版本变更的渐进放量与回滚。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #startRollout}：先把当前观测状态整体备份为快照（回滚目标），
 *       再写入新版本期望，percentage 取值 1-99 表示放量中。</li>
 *   <li>{@link #advance}：提高放量比例；到 100 即 STABLE。</li>
 *   <li>{@link #rollback}：只能对 ROLLING_OUT 的发布执行；恢复快照（回到上一个稳定版本的现场）、
 *       状态置 ROLLED_BACK，并跑一次对账。回滚后若期望未再改动，该对账报告 id
 *             与发布前历史上某次对账报告 id 完全相同（内容寻址）。</li>
 *   <li>已经 ROLLED_BACK 的发布再回滚、再推进、再以此版本开新发布都被拒绝；
 *       重复触发回滚只得到拒绝异常，绝不产生第二次变更。</li>
 * </ul>
 */
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final StateStore store;
    private final Reconciler reconciler;

    public RolloutService(StateStore store, Reconciler reconciler) {
        this.store = store;
        this.reconciler = reconciler;
    }

    public ReleaseRecord startRollout(String environment, ResourceId resource, String version,
                                      List<DesiredResource> desired, int percentage) {
        if (percentage < 1 || percentage > 99) {
            throw new RollbackException("首次放量比例必须在 1-99 之间，收到：" + percentage);
        }
        if (version == null || version.isBlank()) {
            throw new RollbackException("发布版本号不能为空");
        }
        boolean targetExists = desired.stream().anyMatch(d -> d.id().equals(resource));
        if (!targetExists) {
            throw new RollbackException("期望状态中不存在资源 " + resource + "，无法发布");
        }

        // 已经回滚过的版本不允许再发：避免对回滚版本重复操作
        boolean versionRolledBack = store.listReleaseRecords(environment).stream()
                .anyMatch(r -> r.resource().equals(resource)
                        && version.equals(r.version())
                        && r.state() == ReleaseState.ROLLED_BACK);
        if (versionRolledBack) {
            throw new RollbackException("版本 " + version + " 对资源 " + resource
                    + " 已经回滚过，拒绝再次发布该版本");
        }

        String releaseId = "rel-" + UUID.randomUUID();

        // 记录上一个稳定版本（从当前观测或同资源 STABLE 发布中取）
        String previousVersion = currentObservedVersion(environment, resource);

        // 备份现场：回滚后期望与观测都必须恢复到发布前
        List<Map<String, Object>> snapshot =
                List.copyOf(store.loadObservedRaw(environment));
        store.saveReleaseSnapshot(releaseId, snapshot);
        store.saveReleaseDesiredSnapshot(releaseId,
                List.copyOf(store.loadDesired(environment)));

        store.saveDesired(environment, desired);

        Instant now = Instant.now();
        ReleaseRecord record = new ReleaseRecord(releaseId, environment, resource, version,
                previousVersion, percentage, ReleaseState.ROLLING_OUT, now, now, null);
        store.saveReleaseRecord(record);
        log.info("[{}] 开始放量 {}@{} 比例 {}%（上一稳定版本 {}，快照 {}）",
                environment, resource, version, percentage, previousVersion, releaseId);
        return record;
    }

    public ReleaseRecord advance(String environment, String releaseId, int percentage) {
        ReleaseRecord current = find(environment, releaseId);
        if (current.state() == ReleaseState.ROLLED_BACK) {
            throw new RollbackException("发布 " + releaseId + " 已回滚，拒绝继续推进");
        }
        if (percentage <= current.percentage()) {
            throw new RollbackException("放量比例只能增加：当前 " + current.percentage()
                    + "%，请求 " + percentage + "%");
        }
        if (percentage > 100) {
            throw new RollbackException("放量比例不能超过 100，收到：" + percentage);
        }
        ReleaseState newState = percentage == 100 ? ReleaseState.STABLE : ReleaseState.ROLLING_OUT;
        ReleaseRecord updated = new ReleaseRecord(current.releaseId(), environment,
                current.resource(), current.version(), current.previousVersion(), percentage,
                newState, current.createdAt(), Instant.now(), current.rollbackBaselineReportId());
        store.saveReleaseRecord(updated);
        log.info("[{}] 放量 {} 推进到 {}%，状态 {}",
                environment, releaseId, percentage, newState);
        return updated;
    }

    public ReconcileReport rollback(String environment, String releaseId) {
        ReleaseRecord current = find(environment, releaseId);
        if (current.state() == ReleaseState.ROLLED_BACK) {
            // 重复回滚：明确拒绝，不产生任何变更
            throw new RollbackException("发布 " + releaseId + " 已经回滚过，拒绝重复回滚");
        }
        if (current.state() == ReleaseState.STABLE) {
            throw new RollbackException("发布 " + releaseId
                    + " 已全量稳定，按约定回滚仅用于放量中异常，拒绝回滚");
        }

        List<Map<String, Object>> snapshot = store.loadReleaseSnapshot(releaseId)
                .orElseThrow(() -> new RollbackException(
                        "发布 " + releaseId + " 的回滚快照丢失，无法回滚"));
        List<DesiredResource> desiredSnapshot = store.loadReleaseDesiredSnapshot(releaseId)
                .orElseThrow(() -> new RollbackException(
                        "发布 " + releaseId + " 的期望快照丢失，无法回滚"));
        // 期望与观测一起恢复，回滚后的对账才可能和历史报告对上
        store.saveDesired(environment, desiredSnapshot);
        store.saveObservedRaw(environment, snapshot);

        // 回滚后的对账结论即基线；期望未变时它与发布前某份历史报告内容一致、id 相同
        ReconcileReport baseline = reconciler.reconcile(environment);

        ReleaseRecord updated = new ReleaseRecord(current.releaseId(), environment,
                current.resource(), current.version(), current.previousVersion(), 0,
                ReleaseState.ROLLED_BACK, current.createdAt(), Instant.now(),
                baseline.reportId());
        store.saveReleaseRecord(updated);
        log.warn("[{}] 发布 {} 已回滚 {}@{} -> 上一稳定版本 {}，对账基线报告 {}",
                environment, releaseId, current.resource(), current.version(),
                current.previousVersion(), baseline.reportId());
        return baseline;
    }

    private ReleaseRecord find(String environment, String releaseId) {
        return store.listReleaseRecords(environment).stream()
                .filter(r -> r.releaseId().equals(releaseId))
                .findFirst()
                .orElseThrow(() -> new RollbackException(
                        "环境 " + environment + " 中不存在发布 " + releaseId));
    }

    private String currentObservedVersion(String environment, ResourceId resource) {
        for (Map<String, Object> raw : store.loadObservedRaw(environment)) {
            Object t = raw.get("type");
            Object n = raw.get("name");
            if (t instanceof String && n instanceof String
                    && t.equals(resource.type()) && n.equals(resource.name())) {
                Object v = raw.get("version");
                return v instanceof String ? (String) v : null;
            }
        }
        // 观测里没有，再查最近一次稳定发布
        return store.listReleaseRecords(environment).stream()
                .filter(r -> r.resource().equals(resource)
                        && r.state() == ReleaseState.STABLE)
                .reduce((a, b) -> b)
                .map(ReleaseRecord::version)
                .orElse(null);
    }
}
