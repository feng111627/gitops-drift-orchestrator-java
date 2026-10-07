package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/**
 * 一次对账的完整结论。内容确定：同一份输入重复执行，报告逐项一致。
 */
public record ReconcileReport(
        String env,
        List<ReconcileItem> items,
        int syncedCount,
        int driftedCount,
        int missingCount,
        int extraCount,
        int invalidCount,
        boolean clean
) {
}
