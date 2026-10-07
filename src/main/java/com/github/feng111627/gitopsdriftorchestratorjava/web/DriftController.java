package com.github.feng111627.gitopsdriftorchestratorjava.web;

import com.github.feng111627.gitopsdriftorchestratorjava.model.FailureReason;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ReconcileReport;
import com.github.feng111627.gitopsdriftorchestratorjava.model.RolloutRecord;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncExecution;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ReconcileService;
import com.github.feng111627.gitopsdriftorchestratorjava.rollout.RolloutService;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncException;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 入口：对账 / 同步 / 渐进发布 / 回滚。
 *
 * <ul>
 *   <li>POST /api/envs/{env}/reconcile[?snapshot=true] 对账（可选落历史快照）</li>
 *   <li>POST /api/envs/{env}/sync 触发同步；并发被拒绝返回 409</li>
 *   <li>POST /api/envs/{env}/rollouts/{resourceId}/start|promote|rollback</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DriftController {

    private final ReconcileService reconcileService;
    private final SyncOrchestrator syncOrchestrator;
    private final RolloutService rolloutService;

    public DriftController(ReconcileService reconcileService,
                           SyncOrchestrator syncOrchestrator,
                           RolloutService rolloutService) {
        this.reconcileService = reconcileService;
        this.syncOrchestrator = syncOrchestrator;
        this.rolloutService = rolloutService;
    }

    @PostMapping("/envs/{env}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable String env,
                                       @RequestParam(defaultValue = "false") boolean snapshot) {
        if (snapshot) {
            ReconcileService.ReconcileResult result = reconcileService.reconcileAndSnapshot(env);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("snapshotId", result.snapshotId());
            body.put("report", result.report());
            return ResponseEntity.ok(body);
        }
        ReconcileReport report = reconcileService.reconcile(env);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/envs/{env}/sync")
    public SyncExecution sync(@PathVariable String env) {
        return syncOrchestrator.trigger(env);
    }

    @PostMapping("/envs/{env}/rollouts/{resourceId}/start")
    public RolloutRecord startRollout(@PathVariable String env,
                                      @PathVariable String resourceId,
                                      @RequestBody Map<String, Object> body) {
        return rolloutService.start(env, resourceId,
                String.valueOf(body.getOrDefault("newVersion", "")),
                intParam(body, "percentage", 10));
    }

    @PostMapping("/envs/{env}/rollouts/{resourceId}/promote")
    public RolloutRecord promoteRollout(@PathVariable String env,
                                        @PathVariable String resourceId,
                                        @RequestBody Map<String, Object> body) {
        return rolloutService.promote(env, resourceId,
                intParam(body, "percentage", 100));
    }

    @PostMapping("/envs/{env}/rollouts/{resourceId}/rollback")
    public RolloutRecord rollback(@PathVariable String env,
                                  @PathVariable String resourceId,
                                  @RequestBody(required = false) Map<String, Object> body) {
        String snapshotId = body == null ? null : String.valueOf(body.get("snapshotId"));
        return rolloutService.rollback(env, resourceId, snapshotId);
    }

    private static int intParam(Map<String, Object> body, String key, int dflt) {
        Object v = body.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new SyncException(FailureReason.VALIDATION_FAILED,
                    "参数 " + key + " 必须是整数，实际: " + v);
        }
    }

    @ExceptionHandler(SyncException.class)
    public ResponseEntity<Map<String, String>> handleSync(SyncException ex) {
        HttpStatus status = ex.getReason() == FailureReason.CONCURRENT_SYNC_REJECTED
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", ex.getReason().name());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
