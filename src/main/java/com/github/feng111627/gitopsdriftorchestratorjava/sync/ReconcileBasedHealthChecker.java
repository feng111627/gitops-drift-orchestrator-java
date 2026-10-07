package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.ItemStatus;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileItem;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 默认健康检查：波次应用后重新对账，本波资源全部 SYNCED 才算健康；
 * 在 timeoutMillis 内按固定间隔轮询，超时抛 {@link FailureReason#HEALTH_TIMEOUT}。
 */
@Component
public class ReconcileBasedHealthChecker implements HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(ReconcileBasedHealthChecker.class);

    private final ReconcileService reconcileService;
    private final long pollIntervalMillis;

    public ReconcileBasedHealthChecker(
            ReconcileService reconcileService,
            @Value("${drift.health.poll-interval-ms:50}") long pollIntervalMillis) {
        this.reconcileService = reconcileService;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    @Override
    public boolean check(String env, List<String> resourceIds, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        int attempt = 0;
        ReconcileReport last = null;
        while (true) {
            attempt++;
            last = reconcileService.reconcile(env);
            List<ReconcileItem> failing = last.items().stream()
                    .filter(i -> resourceIds.stream().anyMatch(
                            r -> r.equalsIgnoreCase(i.resourceId())))
                    .filter(i -> i.status() != ItemStatus.SYNCED)
                    .toList();
            if (failing.isEmpty()) {
                log.info("健康检查通过 env={} waveResources={} attempts={}",
                        env, resourceIds, attempt);
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                String detail = failing.stream()
                        .map(i -> i.resourceId() + "=" + i.status()
                                + "(" + i.detail() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("未知");
                log.warn("健康检查超时 env={} 资源={} attempts={} 未通过项: {}",
                        env, resourceIds, attempt,
                        detail.toLowerCase(Locale.ROOT));
                throw new SyncException(FailureReason.HEALTH_TIMEOUT,
                        "波次健康检查在 " + timeoutMillis + "ms 内未通过，未通过资源: " + detail);
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SyncException(FailureReason.HEALTH_TIMEOUT,
                        "健康检查等待被中断: " + resourceIds);
            }
        }
    }
}
