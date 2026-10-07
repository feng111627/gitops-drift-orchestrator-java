package com.github.feng111627.gitopsdriftorchestratorjava.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ItemStatus;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileItem;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceDescriptor;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncException;
import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对账服务：逐项比对期望状态与实际状态，产出稳定结论。
 *
 * <p>确定性约定：
 * <ul>
 *   <li>结论只取决于输入内容，不含时间戳/随机值，同一份输入重复执行逐项一致；</li>
 *   <li>结果按资源 id（小写）排序，坏数据按其所在行号定位，顺序稳定；</li>
 *   <li>观测条目字段缺失 / 类型错误 / JSON 损坏不跳过，单独标记 INVALID 并注明行号与原因。</li>
 * </ul>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 对外输出（快照/接口）用可读缩进。 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 写回 observed.jsonl 的观测行必须是紧凑单行（JSONL 按行解析），
     * 绝不能使用缩进 MAPPER，否则一条资源会被拆成多行而误判成坏数据。
     */
    public static final ObjectMapper JSONL_MAPPER = new ObjectMapper();

    private final StateStore stateStore;

    public ReconcileService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** 解析期望状态；期望状态是权威输入，结构性问题直接报 VALIDATION_FAILED。 */
    public List<ResourceDescriptor> parseDesired(String env) {
        String raw = stateStore.readDesired(env);
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "期望状态不是合法 JSON（env=" + env + "）: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "期望状态根节点必须是 JSON 数组（env=" + env + "）");
        }
        List<ResourceDescriptor> desired = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String where = "desired.json[" + i + "]";
            String id = requireText(node, "id", where);
            String type = optionalText(node, "type", where);
            String version = optionalText(node, "version", where);
            String value = optionalText(node, "desiredValue", where);
            List<String> dependsOn = new ArrayList<>();
            JsonNode deps = node.get("dependsOn");
            if (deps != null && !deps.isNull()) {
                if (!deps.isArray()) {
                    throw new SyncException(FailureReason.VALIDATION_FAILED,
                            where + " 的 dependsOn 必须是字符串数组");
                }
                for (JsonNode dep : deps) {
                    if (!dep.isTextual()) {
                        throw new SyncException(FailureReason.VALIDATION_FAILED,
                                where + " 的 dependsOn 含有非字符串元素");
                    }
                    dependsOn.add(dep.asText());
                }
            }
            desired.add(new ResourceDescriptor(id, type, version, value, List.copyOf(dependsOn)));
        }
        return desired;
    }

    private static String requireText(JsonNode node, String field, String where) {
        if (node == null || !node.isObject()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED, where + " 必须是 JSON 对象");
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED, where + " 缺少字段: " + field);
        }
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    where + " 字段 " + field + " 必须是非空字符串，实际类型=" + nodeType(v));
        }
        return v.asText();
    }

    private static String optionalText(JsonNode node, String field, String where) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    where + " 字段 " + field + " 必须是字符串，实际类型=" + nodeType(v));
        }
        return v.asText();
    }

    private static String nodeType(JsonNode v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isObject()) {
            return "object";
        }
        if (v.isArray()) {
            return "array";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        return v.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 观测条目解析结果：要么是合法观测，要么是带定位与原因的坏条目。
     */
    private sealed interface Observed permits GoodObserved, BadObserved {
        int line();
    }

    private record GoodObserved(String id, String type, String version, String value, int line)
            implements Observed {
    }

    private record BadObserved(String id, String problem, int line) implements Observed {
    }

    private List<Observed> parseObserved(String env) {
        String raw = stateStore.readObserved(env);
        List<Observed> result = new ArrayList<>();
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue; // 空行不算观测，也不算坏数据
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception e) {
                result.add(new BadObserved(null,
                        "JSON 格式损坏，无法解析: " + e.getMessage(), lineNo));
                continue;
            }
            if (node == null || !node.isObject()) {
                result.add(new BadObserved(null,
                        "观测条目必须是 JSON 对象，实际为 " + nodeType(node), lineNo));
                continue;
            }
            String id = null;
            List<String> problems = new ArrayList<>();
            JsonNode idNode = node.get("id");
            if (idNode == null || idNode.isNull()) {
                problems.add("缺少字段: id");
            } else if (!idNode.isTextual()) {
                problems.add("字段 id 必须是字符串，实际类型=" + nodeType(idNode));
            } else if (idNode.asText().isBlank()) {
                problems.add("字段 id 为空字符串");
            } else {
                id = idNode.asText();
            }
            String type = null;
            String version = null;
            String value = null;
            JsonNode typeNode = node.get("type");
            if (typeNode != null && !typeNode.isNull()) {
                if (!typeNode.isTextual()) {
                    problems.add("字段 type 必须是字符串，实际类型=" + nodeType(typeNode));
                } else {
                    type = typeNode.asText();
                }
            }
            JsonNode versionNode = node.get("version");
            if (versionNode != null && !versionNode.isNull()) {
                if (!versionNode.isTextual()) {
                    problems.add("字段 version 必须是字符串，实际类型=" + nodeType(versionNode));
                } else {
                    version = versionNode.asText();
                }
            }
            JsonNode valueNode = node.get("observedValue");
            if (valueNode != null && !valueNode.isNull()) {
                if (!valueNode.isTextual()) {
                    problems.add("字段 observedValue 必须是字符串，实际类型="
                            + nodeType(valueNode));
                } else {
                    value = valueNode.asText();
                }
            }
            if (problems.isEmpty()) {
                result.add(new GoodObserved(id, type, version, value, lineNo));
            } else {
                result.add(new BadObserved(id, String.join("; ", problems), lineNo));
            }
        }
        return result;
    }

    /**
     * 执行一次对账。同一份输入重复执行，返回报告逐项一致（幂等、确定性）。
     */
    public ReconcileReport reconcile(String env) {
        List<ResourceDescriptor> desired = parseDesired(env);
        List<Observed> observed = parseObserved(env);

        Map<String, GoodObserved> goodById = new LinkedHashMap<>();
        Map<String, int[]> duplicateRanges = new LinkedHashMap<>(); // id -> [首行, 末行]
        List<ReconcileItem> items = new ArrayList<>();

        for (Observed o : observed) {
            switch (o) {
                case BadObserved bad -> items.add(new ReconcileItem(
                        bad.id(), ItemStatus.INVALID,
                        "observed.jsonl 第 " + bad.line() + " 行观测无效: " + bad.problem(),
                        "observed.jsonl:L" + bad.line()));
                case GoodObserved good -> {
                    int[] range = duplicateRanges.get(good.id());
                    if (range != null) {
                        // 第三次及以后重复：只推进末行
                        range[1] = good.line();
                    } else if (goodById.containsKey(good.id())) {
                        // 第一次发现重复：记录首行与当前行，并从正常集合移除
                        duplicateRanges.put(good.id(), new int[]{goodById.get(good.id()).line(),
                                good.line()});
                        goodById.remove(good.id());
                    } else {
                        goodById.put(good.id(), good);
                    }
                }
            }
        }
        duplicateRanges.forEach((id, range) -> {
            String msg = "observed.jsonl 中资源 id 重复，至少出现于第 " + range[0]
                    + " 行和第 " + range[1] + " 行，无法唯一对账";
            items.add(new ReconcileItem(id, ItemStatus.INVALID, msg,
                    "observed.jsonl:L" + range[0]));
        });

        int synced = 0;
        int drifted = 0;
        int missing = 0;
        int extra = 0;
        int invalid = 0;

        Map<String, ResourceDescriptor> desiredById = new LinkedHashMap<>();
        desired.forEach(d -> desiredById.put(d.id(), d));

        for (GoodObserved good : goodById.values()) {
            ResourceDescriptor d = desiredById.get(good.id());
            if (d == null) {
                items.add(new ReconcileItem(good.id(), ItemStatus.EXTRA,
                        "实际状态中存在，但期望状态未声明该资源（位于 observed.jsonl 第 "
                                + good.line() + " 行）",
                        "observed.jsonl:L" + good.line()));
                extra++;
                continue;
            }
            List<String> diffs = new ArrayList<>();
            if (!equalsNullable(d.desiredValue(), good.value())) {
                diffs.add("内容不一致: 期望[" + d.desiredValue() + "] 实际[" + good.value() + "]");
            }
            if (!equalsNullable(d.version(), good.version())) {
                diffs.add("版本不一致: 期望[" + d.version() + "] 实际[" + good.version() + "]");
            }
            if (!equalsNullable(d.type(), good.type())) {
                diffs.add("类型不一致: 期望[" + d.type() + "] 实际[" + good.type() + "]");
            }
            if (diffs.isEmpty()) {
                items.add(new ReconcileItem(good.id(), ItemStatus.SYNCED,
                        "期望与实际一致", "observed.jsonl:L" + good.line()));
                synced++;
            } else {
                items.add(new ReconcileItem(good.id(), ItemStatus.DRIFTED,
                        String.join("; ", diffs), "observed.jsonl:L" + good.line()));
                drifted++;
            }
        }

        for (ResourceDescriptor d : desired) {
            // 重复 id 已作为 INVALID 报告，不再重复计 MISSING
            if (!goodById.containsKey(d.id()) && !duplicateRanges.containsKey(d.id())) {
                items.add(new ReconcileItem(d.id(), ItemStatus.MISSING,
                        "期望状态声明了该资源，但实际状态缺失", "desired.json"));
                missing++;
            }
        }

        invalid = (int) items.stream().filter(i -> i.status() == ItemStatus.INVALID).count();

        // 稳定排序：先按资源 id（小写，null 最后），再按状态顺序，最后按定位
        items.sort((a, b) -> {
            String ka = a.resourceId() == null ? "￿" : a.resourceId().toLowerCase(Locale.ROOT);
            String kb = b.resourceId() == null ? "￿" : b.resourceId().toLowerCase(Locale.ROOT);
            int c = ka.compareTo(kb);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(statusOrder(a.status()), statusOrder(b.status()));
            if (c != 0) {
                return c;
            }
            return String.valueOf(a.location()).compareTo(String.valueOf(b.location()));
        });

        boolean clean = drifted == 0 && missing == 0 && extra == 0 && invalid == 0;
        ReconcileReport report = new ReconcileReport(env, List.copyOf(items),
                synced, drifted, missing, extra, invalid, clean);
        log.info("对账完成 env={} synced={} drifted={} missing={} extra={} invalid={} clean={}",
                env, synced, drifted, missing, extra, invalid, clean);
        return report;
    }

    private static int statusOrder(ItemStatus s) {
        return switch (s) {
            case SYNCED -> 0;
            case DRIFTED -> 1;
            case MISSING -> 2;
            case EXTRA -> 3;
            case INVALID -> 4;
        };
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 执行对账并持久化一份历史快照，返回（报告，快照标识）。 */
    public ReconcileResult reconcileAndSnapshot(String env) {
        ReconcileReport report = reconcile(env);
        String json;
        try {
            json = MAPPER.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("对账报告序列化失败", e);
        }
        String snapshotId = stateStore.saveReconcileSnapshot(env, json);
        log.info("对账快照已保存 env={} snapshot={}", env, snapshotId);
        return new ReconcileResult(report, snapshotId);
    }

    /** 对账结果 + 快照标识 */
    public record ReconcileResult(ReconcileReport report, String snapshotId) {
    }
}
