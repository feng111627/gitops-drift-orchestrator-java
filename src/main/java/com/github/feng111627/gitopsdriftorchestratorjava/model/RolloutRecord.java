package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/**
 * 一次带版本渐进发布的记录。
 *
 * @param rolloutId        发布 id
 * @param env              环境
 * @param resourceId       被发布的资源
 * @param newVersion       正在放量的新版本
 * @param stableVersion    上一个稳定版本（回滚目标）
 * @param percentage       当前放量百分比（0-100）
 * @param phase            发布阶段
 * @param history          历次放量百分比轨迹（稳定、可重复验证）
 * @param rollbackSnapshot 回滚所对齐的历史对账快照标识；未回滚为 null
 * @param detail           说明（拒绝原因等）
 */
public record RolloutRecord(
        String rolloutId,
        String env,
        String resourceId,
        String newVersion,
        String stableVersion,
        int percentage,
        RolloutPhase phase,
        List<Integer> history,
        String rollbackSnapshot,
        String detail
) {
}
