package com.github.feng111627.gitopsdriftorchestratorjava.model;

/** 对账逐项结果。detail 用人类可读语言说明问题所在。 */
public record ReconcileItem(ResourceId id, ItemStatus status, String detail)
        implements Comparable<ReconcileItem> {

    public ReconcileItem {
        if (id == null) {
            throw new IllegalArgumentException("对账条目缺少资源标识");
        }
        if (status == null) {
            throw new IllegalArgumentException("对账条目缺少结论状态");
        }
        detail = detail == null ? "" : detail;
    }

    @Override
    public int compareTo(ReconcileItem o) {
        int c = this.id.compareTo(o.id);
        if (c != 0) {
            return c;
        }
        return this.status.compareTo(o.status);
    }
}
