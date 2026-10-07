package com.github.feng111627.gitopsdriftorchestratorjava.store;

import com.github.feng111627.gitopsdriftorchestratorjava.model.*;

import java.util.List;
import java.util.Optional;

/**
 * 环境状态存储（本项目用本地文件模拟）。
 * 每个环境有期望状态、观测状态、对账报告归档、同步记录、发布记录。
 */
public interface StateStore {

    /** 读取期望状态；文件缺失时返回空列表。 */
    List<DesiredResource> loadDesired(String environment);

    /** 读取观测状态的原始条目（JSON 对象，不做结构假设，交给对账器校验）。 */
    List<java.util.Map<String, Object>> loadObservedRaw(String environment);

    /** 持久化期望状态（全量覆盖）。 */
    void saveDesired(String environment, List<DesiredResource> desired);

    /** 持久化观测状态（全量覆盖，模拟实际状态被同步动作改变）。 */
    void saveObservedRaw(String environment, List<java.util.Map<String, Object>> observed);

    /** 归档一份对账报告，返回与内容绑定的 reportId；已存在同内容报告时幂等返回原 id。 */
    String archiveReport(String environment, ReconcileReport report);

    /** 按 id 取历史对账报告。 */
    Optional<ReconcileReport> loadReport(String environment, String reportId);

    List<ReconcileReport> listReports(String environment);

    void saveSyncRecord(SyncRecord record);
    List<SyncRecord> listSyncRecords(String environment);

    void saveReleaseRecord(ReleaseRecord record);
    List<ReleaseRecord> listReleaseRecords(String environment);

    /** 为发布保存观测状态快照（回滚目标）。 */
    void saveReleaseSnapshot(String releaseId, List<java.util.Map<String, Object>> observed);
    Optional<List<java.util.Map<String, Object>>> loadReleaseSnapshot(String releaseId);

    /** 为发布保存期望状态快照（回滚时必须连同期望一起恢复）。 */
    void saveReleaseDesiredSnapshot(String releaseId, List<DesiredResource> desired);
    Optional<List<DesiredResource>> loadReleaseDesiredSnapshot(String releaseId);
}
