package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;

/** 同一环境已有同步在执行，拒绝本次触发。 */
public class ConcurrentSyncException extends RuntimeException {
    public ConcurrentSyncException(String message) { super(message); }
    public FailureReason reason() { return FailureReason.CONCURRENT_SYNC; }
}
