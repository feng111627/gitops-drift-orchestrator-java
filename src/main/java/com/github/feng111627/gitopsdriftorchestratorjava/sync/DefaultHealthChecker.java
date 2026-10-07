package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;

/** 默认健康检查：模拟“应用即健康”，立即返回健康。 */
public class DefaultHealthChecker implements HealthChecker {

    @Override
    public String check(DesiredResource resource, long timeoutMillis) {
        return null;
    }
}
