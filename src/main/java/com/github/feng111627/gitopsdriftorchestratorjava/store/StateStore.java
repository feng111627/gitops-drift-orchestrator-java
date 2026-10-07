package com.github.feng111627.gitopsdriftorchestratorjava.store;

/**
 * 本地文件/本地存储抽象。模拟 GitOps 的期望状态与实际状态，
 * 同时承载同步锁、历史快照、发布记录等编排状态。
 */
public interface StateStore {

    /** 期望状态原文（JSON 数组，每个元素为资源描述） */
    String readDesired(String env);

    /** 整体覆盖期望状态（回滚时把目标版本改回稳定版本需要） */
    void writeDesired(String env, String content);

    /** 实际状态原文（JSONL，每行一条观测；允许坏行存在） */
    String readObserved(String env);

    /** 用新内容整体覆盖实际状态（模拟 apply） */
    void writeObserved(String env, String content);

    /** 保存某次对账结论快照，返回快照标识 */
    String saveReconcileSnapshot(String env, String reportJson);

    /** 读取历史对账快照原文；不存在返回 null */
    String readReconcileSnapshot(String env, String snapshotId);

    /** 列出环境的历史对账快照标识（按时间先后） */
    java.util.List<String> listReconcileSnapshots(String env);

    /**
     * 尝试为环境加同步锁。
     *
     * @param executionId 本次执行 id
     * @return true 表示获得锁；false 表示已有同步在进行
     */
    boolean tryAcquireSyncLock(String env, String executionId);

    /** 释放同步锁 */
    void releaseSyncLock(String env, String executionId);

    /** 读取发布记录原文；不存在返回 null */
    String readRollout(String env, String resourceId);

    /** 写入发布记录 */
    void writeRollout(String env, String resourceId, String json);

    /** 持久化一次同步执行记录 */
    void saveSyncExecution(String env, String executionId, String json);
}
