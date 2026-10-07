package com.github.feng111627.gitopsdriftorchestratorjava.model;

/**
 * 单个波次的推进状态。
 */
public enum WaveState {
    PENDING,
    APPLIED,
    HEALTH_PASSED,
    FAILED,
    SKIPPED
}
