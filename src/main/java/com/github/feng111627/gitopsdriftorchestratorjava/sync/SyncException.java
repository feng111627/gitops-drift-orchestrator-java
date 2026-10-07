package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;

/**
 * 同步/发布编排层面的可区分失败。
 */
public class SyncException extends RuntimeException {

    private final FailureReason reason;

    public SyncException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason getReason() {
        return reason;
    }
}
