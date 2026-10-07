package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/**
 * 一次环境同步执行的完整记录（失败时保留已应用部分的痕迹）。
 */
public record SyncExecution(
        String executionId,
        String env,
        SyncState state,
        FailureReason failureReason,
        String failureDetail,
        List<WaveRecord> waves,
        long startedAtEpochMillis,
        long finishedAtEpochMillis
) {
}
