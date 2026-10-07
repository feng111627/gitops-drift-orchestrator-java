package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;
import java.util.Map;

/**
 * 期望状态里的一条资源声明。
 * spec 为期望配置；dependsOn 声明依赖资源（必须先就绪）。
 */
public record DesiredResource(ResourceId id, String version, Map<String, Object> spec,
                              List<ResourceId> dependsOn) {
    public DesiredResource {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        spec = spec == null ? Map.of() : Map.copyOf(spec);
    }
}
