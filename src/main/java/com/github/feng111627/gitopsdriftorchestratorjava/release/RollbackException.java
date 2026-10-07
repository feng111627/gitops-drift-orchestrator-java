package com.github.feng111627.gitopsdriftorchestratorjava.release;

/** 回滚被拒绝（版本已回滚过 / 重复触发 / 发布不存在）。 */
public class RollbackException extends RuntimeException {
    public RollbackException(String message) { super(message); }
}
