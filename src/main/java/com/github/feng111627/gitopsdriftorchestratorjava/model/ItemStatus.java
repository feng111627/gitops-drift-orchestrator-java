package com.github.feng111627.gitopsdriftorchestratorjava.model;

/**
 * 逐项对账结论。
 */
public enum ItemStatus {
    /** 期望与实际一致 */
    SYNCED,
    /** 资源都存在，但内容/版本有漂移 */
    DRIFTED,
    /** 期望里有、实际里缺失 */
    MISSING,
    /** 实际里有、期望里没有 */
    EXTRA,
    /** 实际状态条目本身坏掉（字段缺失/类型错误/格式损坏），无法参与对账 */
    INVALID
}
