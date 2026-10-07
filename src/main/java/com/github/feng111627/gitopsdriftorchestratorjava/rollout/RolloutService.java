package com.github.feng111627.gitopsdriftorchestratorjava.rollout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceDescriptor;
import com.github.feng111627.gitopsdriftorchestratorjava.model.RolloutPhase;
import com.github.feng111627.gitopsdriftorchestratorjava.model.RolloutRecord;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 渐进发布与回滚。
 *
 * <p>模型：资源以版本作为发布单位。实际状态里资源的 {@code observedValue} 记录为
 * 加权分布文本（{@code 版本@百分比}，多个权重以 {@code |} 连接，如
 * {@code "v2@10|v1@90"}）；期望状态中该资源始终指向“当前稳定版本”。
 *
 * <ul>
 *   <li>{@code start}：记录稳定版本与初始百分比，只改实际状态的权重分布；
 *       此时期望(稳定)与实际(混合)必然 DRIFTED，表示发布在途。</li>
 *   <li>{@code promote}：百分比必须严格递增；到 100% 时发布 COMPLETED，
 *       同时把期望状态的目标版本切到新版本，此后该资源重新 SYNCED。</li>
 *   <li>{@code rollback}：只能发生在完成前。把实际状态恢复为稳定版本权重 100%
 *       （期望从未改过，仍是稳定版本），随后立即对账并与指定的历史对账快照
 *             逐项核对，一致才确认 ROLLED_BACK。</li>
 *   <li>拒绝与幂等：已回滚的发布重复回滚、对已回滚/已完成的发布再放量、
 *       百分比不递增，一律拒绝且不产生变更。</li>
 * </ul>
 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final StateStore stateStore;
    private final ReconcileService reconcileService;

    public RolloutService(StateStore stateStore, ReconcileService reconcileService) {
        this.stateStore = stateStore;
        this.reconcileService = reconcileService;
    }

    private RolloutRecord load(String env, String resourceId) {
        String raw = stateStore.readRollout(env, resourceId);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode r = ReconcileService.MAPPER.readTree(raw);
            List<Integer> history = new ArrayList<>();
            r.withArray("history").forEach(n -> history.add(n.asInt()));
            return new RolloutRecord(
                    r.get("rolloutId").asText(),
                    r.get("env").asText(),
                    r.get("resourceId").asText(),
                    r.get("newVersion").asText(),
                    r.path("stableVersion").isNull() ? null : r.get("stableVersion").asText(),
                    r.get("percentage").asInt(),
                    RolloutPhase.valueOf(r.get("phase").asText()),
                    List.copyOf(history),
                    r.has("rollbackSnapshot") && !r.get("rollbackSnapshot").isNull()
                            ? r.get("rollbackSnapshot").asText() : null,
                    r.has("detail") && !r.get("detail").isNull()
                            ? r.get("detail").asText() : null);
        } catch (Exception e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "发布记录损坏，无法读取: " + resourceId + " (" + e.getMessage() + ")");
        }
    }

    private RolloutRecord save(RolloutRecord record) {
        try {
            stateStore.writeRollout(record.env(), record.resourceId(),
                    ReconcileService.MAPPER.writeValueAsString(record));
        } catch (Exception e) {
            throw new IllegalStateException("发布记录写入失败", e);
        }
        return record;
    }

    /** 开始一次渐进发布（初始百分比）。 */
    public RolloutRecord start(String env, String resourceId, String newVersion,
                               int percentage) {
        if (newVersion == null || newVersion.isBlank()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED, "newVersion 不能为空");
        }
        validatePercentage(percentage);
        RolloutRecord existing = load(env, resourceId);
        if (existing != null && existing.phase() == RolloutPhase.IN_PROGRESS) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "资源 " + resourceId + " 已有进行中的发布 " + existing.rolloutId()
                            + "（" + existing.newVersion() + "@" + existing.percentage()
                            + "%），不能重复开始");
        }
        if (existing != null && existing.phase() == RolloutPhase.ROLLED_BACK) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "资源 " + resourceId + " 的发布 " + existing.rolloutId()
                            + " 已回滚，不允许对该版本再操作，请以新版本重新发起发布");
        }
        ResourceDescriptor desired = requireDesired(env, resourceId);
        String stableVersion = desired.version();
        RolloutRecord record = new RolloutRecord(
                "rollout-" + resourceId + "-" + newVersion,
                env, resourceId, newVersion, stableVersion,
                percentage, RolloutPhase.IN_PROGRESS, List.of(percentage), null,
                "发布已开始，从稳定版本 " + stableVersion + " 向 " + newVersion
                        + " 放量 " + percentage + "%（在途期间对账为 DRIFTED）");
        applyWeightedObserved(env, resourceId, newVersion, percentage, stableVersion);
        log.info("发布开始 env={} resource={} {} -> {} 初始={}%",
                env, resourceId, stableVersion, newVersion, percentage);
        return save(record);
    }

    /** 推进到更高百分比（必须严格递增，达到 100 即 COMPLETED）。 */
    public RolloutRecord promote(String env, String resourceId, int percentage) {
        validatePercentage(percentage);
        RolloutRecord r = requireInProgress(env, resourceId);
        if (percentage <= r.percentage()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "放量百分比必须严格递增: 当前 " + r.percentage() + "%，请求 " + percentage
                            + "%（重复/回退触发被拒绝，不产生变更）");
        }
        List<Integer> history = new ArrayList<>(r.history());
        history.add(percentage);
        boolean completing = percentage >= 100;
        RolloutPhase phase = completing ? RolloutPhase.COMPLETED : RolloutPhase.IN_PROGRESS;
        String detail = completing
                ? "已全量发布并完成，" + r.newVersion() + " 成为新的稳定版本（期望目标已切换）"
                : "已放量至 " + percentage + "%";
        if (completing) {
            applyStableObserved(env, resourceId, r.newVersion());
            switchDesiredVersion(env, resourceId, r.newVersion());
        } else {
            applyWeightedObserved(env, resourceId, r.newVersion(), percentage,
                    r.stableVersion());
        }
        RolloutRecord updated = new RolloutRecord(r.rolloutId(), r.env(), r.resourceId(),
                r.newVersion(), r.stableVersion(), percentage, phase,
                List.copyOf(history), r.rollbackSnapshot(), detail);
        log.info("发布推进 env={} resource={} -> {}% phase={}",
                env, resourceId, percentage, phase);
        return save(updated);
    }

    /** 回滚到上一个稳定版本，并与指定历史对账快照逐项核对。 */
    public RolloutRecord rollback(String env, String resourceId, String targetSnapshotId) {
        RolloutRecord r = requireInProgress(env, resourceId);
        if (targetSnapshotId == null || targetSnapshotId.isBlank()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "必须指定要对齐的历史对账快照 snapshotId");
        }
        String snapshotJson = stateStore.readReconcileSnapshot(env, targetSnapshotId);
        if (snapshotJson == null) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "历史对账快照不存在: " + targetSnapshotId);
        }
        // 恢复实际状态为稳定版本（期望在发布期间从未切换，仍指向稳定版本）
        applyStableObserved(env, resourceId, r.stableVersion());
        ReconcileReport now = reconcileService.reconcile(env);
        ReconcileReport target;
        try {
            target = ReconcileService.MAPPER.readValue(snapshotJson, ReconcileReport.class);
        } catch (Exception e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "历史对账快照损坏: " + targetSnapshotId + " (" + e.getMessage() + ")");
        }
        if (!reportsEquivalent(now, target)) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "回滚后对账结论与快照 " + targetSnapshotId
                            + " 对不上，回滚不予确认（实际状态已恢复为稳定版本，请人工核对）");
        }
        RolloutRecord rolledBack = new RolloutRecord(r.rolloutId(), r.env(),
                r.resourceId(), r.newVersion(), r.stableVersion(), 0,
                RolloutPhase.ROLLED_BACK, r.history(), targetSnapshotId,
                "已回滚到稳定版本 " + r.stableVersion() + "，回滚后对账结论与快照 "
                        + targetSnapshotId + " 逐项一致");
        log.warn("发布回滚 env={} resource={} {} -> {} 对齐快照={}",
                env, resourceId, r.newVersion(), r.stableVersion(), targetSnapshotId);
        return save(rolledBack);
    }

    private RolloutRecord requireInProgress(String env, String resourceId) {
        RolloutRecord r = load(env, resourceId);
        if (r == null) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "资源 " + resourceId + " 没有发布记录");
        }
        if (r.phase() == RolloutPhase.ROLLED_BACK) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "发布 " + r.rolloutId() + " 已回滚（对齐快照 " + r.rollbackSnapshot()
                            + "），重复操作被拒绝，不产生重复变更");
        }
        if (r.phase() == RolloutPhase.COMPLETED) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "发布 " + r.rolloutId() + " 已全量完成，" + r.newVersion()
                            + " 已是稳定版本，不能再放量或回滚");
        }
        return r;
    }

    private ResourceDescriptor requireDesired(String env, String resourceId) {
        return reconcileService.parseDesired(env).stream()
                .filter(d -> d.id().equals(resourceId))
                .findFirst()
                .orElseThrow(() -> new SyncException(FailureReason.VALIDATION_FAILED,
                        "期望状态中不存在资源: " + resourceId));
    }

    private static void validatePercentage(int percentage) {
        if (percentage < 1 || percentage > 100) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "放量百分比必须在 1..100 之间，实际: " + percentage);
        }
    }

    /** 只比较“结论”：逐项 (resourceId, status) 与各计数一致；detail/location 不参与。 */
    static boolean reportsEquivalent(ReconcileReport a, ReconcileReport b) {
        if (a.syncedCount() != b.syncedCount() || a.driftedCount() != b.driftedCount()
                || a.missingCount() != b.missingCount() || a.extraCount() != b.extraCount()
                || a.invalidCount() != b.invalidCount() || a.clean() != b.clean()
                || a.items().size() != b.items().size()) {
            return false;
        }
        for (int i = 0; i < a.items().size(); i++) {
            var x = a.items().get(i);
            var y = b.items().get(i);
            if (!Objects.equals(x.resourceId(), y.resourceId()) || x.status() != y.status()) {
                return false;
            }
        }
        return true;
    }

    private void applyWeightedObserved(String env, String resourceId,
                                       String newVersion, int p, String stableVersion) {
        String distribution = p >= 100
                ? newVersion + "@100"
                : newVersion + "@" + p + "|" + stableVersion + "@" + (100 - p);
        // 在途：版本字段无法唯一表示混合权重，置空，对账必然 DRIFTED（符合预期）
        upsertObserved(env, resourceId, distribution, null);
    }

    private void applyStableObserved(String env, String resourceId, String stableVersion) {
        // 回滚后要与发布前快照逐项一致：版本、内容、类型都必须恢复
        String type = requireDesired(env, resourceId).type();
        upsertObserved(env, resourceId, stableVersion + "@100", stableVersion, type);
    }

    /** 替换/新增 observed.jsonl 中某资源行，其余行（含坏行）原样保留。 */
    private void upsertObserved(String env, String resourceId,
                                String distribution, String version) {
        upsertObserved(env, resourceId, distribution, version,
                requireDesired(env, resourceId).type());
    }

    /** 替换/新增 observed.jsonl 中某资源行，其余行（含坏行）原样保留。 */
    private void upsertObserved(String env, String resourceId, String distribution,
                                String version, String type) {
        List<String> out = new ArrayList<>();
        for (String line : stateStore.readObserved(env).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String id = null;
            try {
                JsonNode node = ReconcileService.MAPPER.readTree(trimmed);
                if (node != null && node.isObject()) {
                    id = node.path("id").asText(null);
                }
            } catch (Exception ignored) {
                // 坏行保留
            }
            if (resourceId.equals(id)) {
                continue;
            }
            out.add(trimmed);
        }
        ObjectNode node = ReconcileService.MAPPER.createObjectNode();
        node.put("id", resourceId);
        if (type != null) {
            node.put("type", type);
        }
        if (version != null) {
            node.put("version", version);
        }
        node.put("observedValue", distribution);
        try {
            out.add(ReconcileService.JSONL_MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        stateStore.writeObserved(env, String.join("\n", out) + "\n");
    }

    /** 把期望状态里某资源的版本与期望内容切到新版本（仅发布 100% 完成时调用）。 */
    private void switchDesiredVersion(String env, String resourceId, String newVersion) {
        try {
            JsonNode root = ReconcileService.MAPPER.readTree(stateStore.readDesired(env));
            if (!(root instanceof ArrayNode arr)) {
                throw new SyncException(FailureReason.VALIDATION_FAILED,
                        "期望状态根节点必须是数组");
            }
            boolean switched = false;
            for (JsonNode element : arr) {
                if (resourceId.equals(element.path("id").asText(null))
                        && element instanceof ObjectNode obj) {
                    obj.put("version", newVersion);
                    obj.put("desiredValue", newVersion + "@100");
                    switched = true;
                }
            }
            if (!switched) {
                throw new SyncException(FailureReason.VALIDATION_FAILED,
                        "期望状态中不存在资源: " + resourceId);
            }
            stateStore.writeDesired(env,
                    ReconcileService.MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(arr));
        } catch (SyncException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "切换期望版本失败: " + e.getMessage());
        }
    }
}
