package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;

/**
 * 资源健康检查。同步编排器在每波应用后、进入下一波前调用。
 * 模拟实现默认立即通过；测试可注入故障。
 */
public interface HealthChecker {

    /**
     * 在 timeoutMillis 内确认资源健康。
     *
     * @return null 表示健康；非 null 为不健康原因（超时或检查失败）
     * @throws DependencyNotReadyException 该资源依赖的上游尚未就绪（区别于自身超时）
     * @throws InterruptedException        等待期间被中断
     */
    String check(DesiredResource resource, long timeoutMillis)
            throws DependencyNotReadyException, InterruptedException;
}
