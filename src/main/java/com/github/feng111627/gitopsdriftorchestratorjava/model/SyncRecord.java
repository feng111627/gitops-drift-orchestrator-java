package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/** 一次同步执行的记录（含逐波次结论与失败原因）。 */
public record SyncRecord(String runId, String environment, Instant startedAt, Instant finishedAt,
                         SyncState state, FailureReason failureReason, String failureDetail,
                         int totalWaves, int completedWaves, List<ResourceId> appliedResources) {
}
