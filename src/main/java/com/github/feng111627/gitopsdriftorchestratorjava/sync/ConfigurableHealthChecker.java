package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可编排故障的健康检查，供测试与演示使用：
 * 可让指定资源永远不健康（模拟健康检查超时）、或报告依赖未就绪。
 */
public class ConfigurableHealthChecker implements HealthChecker {

    private final Set<ResourceId> neverHealthy = ConcurrentHashMap.newKeySet();
    private final Map<ResourceId, ResourceId> blockedBy = new ConcurrentHashMap<>();
    private final long pollIntervalMillis;

    public ConfigurableHealthChecker() {
        this(10);
    }

    public ConfigurableHealthChecker(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public void markNeverHealthy(ResourceId id) {
        neverHealthy.add(id);
    }

    public void markDependencyBlocked(ResourceId resource, ResourceId dependency) {
        blockedBy.put(resource, dependency);
    }

    public void clearFaults() {
        neverHealthy.clear();
        blockedBy.clear();
    }

    @Override
    public String check(DesiredResource resource, long timeoutMillis)
            throws DependencyNotReadyException, InterruptedException {
        ResourceId blocker = blockedBy.get(resource.id());
        if (blocker != null) {
            throw new DependencyNotReadyException(resource, blocker);
        }
        if (neverHealthy.contains(resource.id())) {
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
            // 模拟在超时时间内反复探测仍不通过
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollIntervalMillis);
                if (neverHealthy.isEmpty()) {
                    return null;
                }
            }
            return "资源 " + resource.id() + " 在 " + timeoutMillis + "ms 内健康检查未通过";
        }
        return null;
    }
}
