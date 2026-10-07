package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.Map;

/**
 * 实际（观测）状态里解析出的一条资源。
 * raw 为原始 JSON 条目；spec/version 仅在结构可用时非空。
 */
public record Observed(ResourceId id, String version, Map<String, Object> spec,
                       Map<String, Object> raw, boolean structural, String structuralError) {
}
