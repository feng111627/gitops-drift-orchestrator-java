package com.github.feng111627.gitopsdriftorchestratorjava.reconcile;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对账器：逐项比较期望与观测状态。
 *
 * <p>规则：
 * <ul>
 *   <li>观测条目必须是 JSON 对象，且含字符串字段 type、name，spec 必须是对象；
 *       version 缺省允许，若非空必须是字符串。任一条不满足标 {@link ItemStatus#INVALID}，
 *       不跳过，detail 说清是第几条、哪个字段、什么问题。</li>
 *   <li>期望有观测无 → MISSING；观测有期望无 → EXTRA；
 *       都有且 spec 与 version 一致 → SYNCED；不一致 → DRIFTED，detail 指出首个差异字段。</li>
 *   <li>结论按资源排序，reportId 由环境 + 全部条目内容哈希得出，
 *       与执行时间无关，因此同一份输入重复跑结论与 id 完全一致。</li>
 * </ul>
 */
public class Reconciler {

    private final StateStore store;

    public Reconciler(StateStore store) {
        this.store = store;
    }

    /** 执行对账并归档报告；报告 id 对相同输入稳定。 */
    public ReconcileReport reconcile(String environment) {
        List<DesiredResource> desired = store.loadDesired(environment);
        List<Map<String, Object>> rawList = store.loadObservedRaw(environment);

        List<ReconcileItem> items = new ArrayList<>();

        // 先解析观测侧；结构坏的条目按在文件中的位置标出
        Map<ResourceId, Observed> goodObserved = new LinkedHashMap<>();
        for (int i = 0; i < rawList.size(); i++) {
            Observed o = parseObserved(rawList.get(i));
            if (o.structural()) {
                // 重复观测同 id 也视为数据坏掉，避免静默覆盖
                if (goodObserved.put(o.id(), o) != null) {
                    items.add(new ReconcileItem(o.id(), ItemStatus.INVALID,
                            "观测状态中资源 " + o.id() + " 在第 " + (i + 1)
                                    + " 条重复出现"));
                }
            } else {
                ResourceId placeHolder = o.id() != null ? o.id()
                        : new ResourceId("_invalid", "第" + (i + 1) + "条");
                items.add(new ReconcileItem(placeHolder, ItemStatus.INVALID,
                        "观测状态第 " + (i + 1) + " 条：" + o.structuralError()));
            }
        }

        items.addAll(compare(desired, new ArrayList<>(goodObserved.values())));
        items.sort(Comparator.naturalOrder());

        String reportId = hash(environment, items);
        // 已归档过同内容报告时沿用首次生成时间，保证完全一致
        Instant generatedAt = store.loadReport(environment, reportId)
                .map(ReconcileReport::generatedAt)
                .orElseGet(Instant::now);
        ReconcileReport report = new ReconcileReport(reportId, environment, generatedAt, items);
        store.archiveReport(environment, report);
        return report;
    }

    /**
     * 解析一条原始观测 JSON。任何结构问题都通过 structural=false + structuralError 表达，
     * 绝不抛异常导致该条目被跳过。
     */
    static Observed parseObserved(Map<String, Object> raw) {
        if (raw == null) {
            return bad(null, "条目本身为 null");
        }
        // Jackson 永远产出 LinkedHashMap，这里做防御性判断
        Object typeObj = raw.get("type");
        Object nameObj = raw.get("name");
        ResourceId id = null;
        if (typeObj instanceof String t && nameObj instanceof String n) {
            try {
                id = new ResourceId(t, n);
            } catch (IllegalArgumentException e) {
                return bad(null, "type/name 为空白：" + e.getMessage());
            }
        } else {
            String problem;
            if (typeObj == null && nameObj == null) {
                problem = "缺少字段 type 和 name";
            } else if (typeObj == null) {
                problem = "缺少字段 type";
            } else if (nameObj == null) {
                problem = "缺少字段 name";
            } else {
                problem = "字段 type、name 必须是字符串，实际类型为 "
                        + typeObj.getClass().getSimpleName() + "/" + nameObj.getClass().getSimpleName();
            }
            return bad(null, problem);
        }

        Object versionObj = raw.get("version");
        String version = null;
        if (versionObj != null) {
            if (!(versionObj instanceof String)) {
                return bad(id, "字段 version 必须是字符串，实际类型为 "
                        + versionObj.getClass().getSimpleName());
            }
            version = (String) versionObj;
        }

        Object specObj = raw.get("spec");
        if (specObj == null) {
            return bad(id, "缺少字段 spec");
        }
        if (!(specObj instanceof Map)) {
            return bad(id, "字段 spec 必须是 JSON 对象，实际类型为 "
                    + specObj.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) specObj;
        return new Observed(id, version, spec, raw, true, null);
    }

    private static Observed bad(ResourceId id, String error) {
        return new Observed(id, null, null, null, false, error);
    }

    /** 比较期望列表与已成功解析的观测列表。 */
    static List<ReconcileItem> compare(List<DesiredResource> desired, List<Observed> observed) {
        List<ReconcileItem> items = new ArrayList<>();
        Map<ResourceId, DesiredResource> desiredMap = new LinkedHashMap<>();
        for (DesiredResource d : desired) {
            desiredMap.put(d.id(), d);
        }
        Map<ResourceId, Observed> observedMap = new LinkedHashMap<>();
        for (Observed o : observed) {
            observedMap.put(o.id(), o);
        }

        for (DesiredResource d : desired) {
            Observed o = observedMap.get(d.id());
            if (o == null) {
                items.add(new ReconcileItem(d.id(), ItemStatus.MISSING,
                        "期望版本 " + d.version() + " 在观测状态中不存在"));
                continue;
            }
            String specDiff = firstSpecDiff(d.spec(), o.spec());
            String versionDiff = d.version() == null ? null
                    : (d.version().equals(o.version()) ? null
                    : "version 期望 " + d.version() + " 实际 " + o.version());
            if (specDiff == null && versionDiff == null) {
                items.add(new ReconcileItem(d.id(), ItemStatus.SYNCED, "已同步"));
            } else {
                List<String> diffs = new ArrayList<>();
                if (versionDiff != null) {
                    diffs.add(versionDiff);
                }
                if (specDiff != null) {
                    diffs.add(specDiff);
                }
                items.add(new ReconcileItem(d.id(), ItemStatus.DRIFTED,
                        "配置漂移：" + String.join("；", diffs)));
            }
        }

        for (Observed o : observed) {
            if (!desiredMap.containsKey(o.id())) {
                items.add(new ReconcileItem(o.id(), ItemStatus.EXTRA,
                        "观测状态多出期望中未声明的资源（实际版本 "
                                + (o.version() == null ? "未设置" : o.version()) + "）"));
            }
        }
        return items;
    }

    /** 递归比较 spec，返回首个差异的点路径；嵌套 Map 按键排序后比较以保证稳定。 */
    @SuppressWarnings("unchecked")
    static String firstSpecDiff(Map<String, Object> expected, Map<String, Object> actual) {
        return diffAt("", new TreeMap<>(expected), new TreeMap<>(actual));
    }

    @SuppressWarnings("unchecked")
    private static String diffAt(String path, Map<String, Object> expected,
                                 Map<String, Object> actual) {
        for (String key : sortedUnion(expected.keySet(), actual.keySet())) {
            String p = path.isEmpty() ? key : path + "." + key;
            if (!expected.containsKey(key)) {
                return "字段 " + p + " 为多余项，期望值中不存在";
            }
            if (!actual.containsKey(key)) {
                return "字段 " + p + " 缺失，期望为 " + expected.get(key);
            }
            Object ev = expected.get(key);
            Object av = actual.get(key);
            if (ev instanceof Map && av instanceof Map) {
                String sub = diffAt(p, new TreeMap<>((Map<String, Object>) ev),
                        new TreeMap<>((Map<String, Object>) av));
                if (sub != null) {
                    return sub;
                }
            } else if (!java.util.Objects.equals(ev, av)) {
                return "字段 " + p + " 期望 " + ev + " 实际 " + av;
            }
        }
        return null;
    }

    private static List<String> sortedUnion(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }

    /** 由环境与全部条目内容计算稳定 id（不含时间戳）。 */
    private static String hash(String environment, List<ReconcileItem> items) {
        StringBuilder sb = new StringBuilder(environment);
        for (ReconcileItem item : items) {
            sb.append('\n').append(item.id()).append('|')
                    .append(item.status()).append('|').append(item.detail());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return "rpt-" + hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
