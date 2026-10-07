package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/**
 * 期望状态中的一个资源描述。
 *
 * @param id           资源唯一标识（环境内唯一）
 * @param type         资源类型
 * @param version      期望版本
 * @param desiredValue 期望内容（与实际内容做稳定对比）
 * @param dependsOn    该资源依赖的其他资源 id，用于划分同步波次
 */
public record ResourceDescriptor(
        String id,
        String type,
        String version,
        String desiredValue,
        List<String> dependsOn
) {
}
