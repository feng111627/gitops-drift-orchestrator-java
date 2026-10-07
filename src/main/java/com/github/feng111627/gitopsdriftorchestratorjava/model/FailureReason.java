package com.github.feng111627.gitopsdriftorchestratorjava.model;

/**
 * 同步/发布失败的可区分原因。
 */
public enum FailureReason {
    /** 期望状态或输入参数校验未通过 */
    VALIDATION_FAILED,
    /** 健康检查在约定时间内未通过 */
    HEALTH_TIMEOUT,
    /** 依赖资源未就绪（依赖波次未通过或依赖项不存在） */
    DEPENDENCY_NOT_READY,
    /** 同一环境已有同步在执行，触发被拒绝 */
    CONCURRENT_SYNC_REJECTED,
    /** 输入数据格式无法解析 */
    INVALID_INPUT
}
