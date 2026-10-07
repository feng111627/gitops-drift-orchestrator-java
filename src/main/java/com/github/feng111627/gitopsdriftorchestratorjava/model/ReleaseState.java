package com.github.feng111627.gitopsdriftorchestratorjava.model;

/** 一次带版本变更的生命周期状态。 */
public enum ReleaseState {
    STABLE,        // 全量稳定
    ROLLING_OUT,   // 渐进放量中
    ROLLED_BACK    // 已回滚（终态，不可再回滚）
}
