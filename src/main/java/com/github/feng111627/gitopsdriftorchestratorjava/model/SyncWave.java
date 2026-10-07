package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/** 一个同步波次：同一波次内的资源无相互依赖，可一起应用。 */
public record SyncWave(int index, List<ResourceId> resources) {
}
