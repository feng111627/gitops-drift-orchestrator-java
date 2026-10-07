package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/** 一次对账的完整结论；同一份输入重复执行应产出内容一致（reportId 相同）。 */
public record ReconcileReport(String reportId, String environment, Instant generatedAt,
                              List<ReconcileItem> items) {

    public ReconcileReport {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public long count(ItemStatus status) {
        return items.stream().filter(i -> i.status() == status).count();
    }

    /** 所有条目均为 SYNCED 才算收敛。 */
    public boolean converged() {
        return items.stream().allMatch(i -> i.status() == ItemStatus.SYNCED);
    }
}
