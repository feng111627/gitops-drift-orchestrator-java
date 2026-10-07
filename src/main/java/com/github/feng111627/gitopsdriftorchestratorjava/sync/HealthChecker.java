package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import java.util.List;

/**
 * 波次健康检查抽象。
 */
public interface HealthChecker {

    /**
     * 检查刚应用的一个波次是否健康。
     *
     * @param env         环境
     * @param resourceIds 本波资源
     * @param timeoutMillis 超时上限
     * @return true 健康
     * @throws SyncException 超时时以 HEALTH_TIMEOUT 抛出
     */
    boolean check(String env, List<String> resourceIds, long timeoutMillis);
}
