package com.github.feng111627.gitopsdriftorchestratorjava.model;

/**
 * 对账报告中的单条结果。
 *
 * @param resourceId 资源 id；对应坏数据时为该条目自带的 id（可能为 null）
 * @param status     对账结论
 * @param detail     人类可读说明；INVALID 时必须说清问题出在哪一项、什么问题
 * @param location   来源定位（如 observed.jsonl 的第 N 行）
 */
public record ReconcileItem(
        String resourceId,
        ItemStatus status,
        String detail,
        String location
) {
}
