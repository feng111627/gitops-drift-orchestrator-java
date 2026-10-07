package com.github.feng111627.gitopsdriftorchestratorjava.model;

/** 同步失败原因分类。 */
public enum FailureReason {
    VALIDATION_FAILED,     // 校验没过（期望状态非法、依赖成环等）
    HEALTH_CHECK_TIMEOUT,  // 健康检查超时/不通过
    DEPENDENCY_NOT_READY,  // 依赖没就绪
    CONCURRENT_SYNC        // 同环境已有同步在跑（拒绝）
}
