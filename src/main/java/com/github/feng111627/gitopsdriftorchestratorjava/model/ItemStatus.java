package com.github.feng111627.gitopsdriftorchestratorjava.model;

/** 对账逐项结论。 */
public enum ItemStatus {
    SYNCED,    // 已同步
    DRIFTED,   // 有漂移
    MISSING,   // 期望有、实际缺失
    EXTRA,     // 实际多出
    INVALID    // 观测条目本身坏掉（缺字段/类型错/格式错）
}
