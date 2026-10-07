package com.github.feng111627.gitopsdriftorchestratorjava.reconcile;

/** 期望状态校验失败（坏声明、未知依赖、依赖成环等）。 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}
