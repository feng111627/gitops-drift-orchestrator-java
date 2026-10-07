package com.github.feng111627.gitopsdriftorchestratorjava.model;

/**
 * 渐进发布阶段。
 */
public enum RolloutPhase {
    /** 放量进行中（百分比 < 100） */
    IN_PROGRESS,
    /** 已全量并确认 */
    COMPLETED,
    /** 已回滚到上一个稳定版本 */
    ROLLED_BACK
}
