package com.github.feng111627.gitopsdriftorchestratorjava.web;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;
import com.github.feng111627.gitopsdriftorchestratorjava.release.RollbackException;
import com.github.feng111627.gitopsdriftorchestratorjava.release.RolloutService;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.ValidationException;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.ConcurrentSyncException;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理接口：
 * GET  /api/envs/{env}/reconcile        触发对账
 * GET  /api/envs/{env}/reports          历史对账报告
 * POST /api/envs/{env}/sync             触发按波次同步（并发触发返回 409）
 * GET  /api/envs/{env}/sync-records     同步执行记录
 * GET  /api/envs/{env}/desired / observed   查看状态原文
 * PUT  /api/envs/{env}/desired          写入期望状态
 * POST /api/envs/{env}/releases         开始放量
 * POST /api/envs/{env}/releases/{id}/advance   推进放量
 * POST /api/envs/{env}/releases/{id}/rollback  回滚
 */
@RestController
@RequestMapping("/api/envs/{env}")
public class ApiController {

    private final Reconciler reconciler;
    private final SyncOrchestrator syncOrchestrator;
    private final RolloutService rolloutService;
    private final StateStore store;

    public ApiController(Reconciler reconciler, SyncOrchestrator syncOrchestrator,
                         RolloutService rolloutService, StateStore store) {
        this.reconciler = reconciler;
        this.syncOrchestrator = syncOrchestrator;
        this.rolloutService = rolloutService;
        this.store = store;
    }

    @GetMapping("/reconcile")
    public ReconcileReport reconcile(@PathVariable String env) {
        return reconciler.reconcile(env);
    }

    @GetMapping("/reports")
    public List<ReconcileReport> reports(@PathVariable String env) {
        return store.listReports(env);
    }

    @PostMapping("/sync")
    public SyncRecord sync(@PathVariable String env) {
        return syncOrchestrator.sync(env);
    }

    @GetMapping("/sync-records")
    public List<SyncRecord> syncRecords(@PathVariable String env) {
        return store.listSyncRecords(env);
    }

    @GetMapping("/desired")
    public List<DesiredResource> desired(@PathVariable String env) {
        return store.loadDesired(env);
    }

    @GetMapping("/observed")
    public List<Map<String, Object>> observed(@PathVariable String env) {
        return store.loadObservedRaw(env);
    }

    @PutMapping("/desired")
    public Map<String, String> putDesired(@PathVariable String env,
                                          @RequestBody List<DesiredResource> desired) {
        store.saveDesired(env, desired);
        return Map.of("result", "saved", "items", String.valueOf(desired.size()));
    }

    public record StartRolloutRequest(ResourceId resource, String version,
                                      List<DesiredResource> desired, int percentage) {
    }

    @PostMapping("/releases")
    public ReleaseRecord startRollout(@PathVariable String env,
                                      @RequestBody StartRolloutRequest req) {
        return rolloutService.startRollout(env, req.resource(), req.version(),
                req.desired(), req.percentage());
    }

    public record AdvanceRequest(int percentage) {
    }

    @PostMapping("/releases/{id}/advance")
    public ReleaseRecord advance(@PathVariable String env, @PathVariable String id,
                                 @RequestBody AdvanceRequest req) {
        return rolloutService.advance(env, id, req.percentage());
    }

    @PostMapping("/releases/{id}/rollback")
    public ReconcileReport rollback(@PathVariable String env, @PathVariable String id) {
        return rolloutService.rollback(env, id);
    }

    @ExceptionHandler(ConcurrentSyncException.class)
    public ResponseEntity<Map<String, String>> handleConcurrent(ConcurrentSyncException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONCURRENT_SYNC", "message", e.getMessage()));
    }

    @ExceptionHandler({ValidationException.class, RollbackException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }
}
