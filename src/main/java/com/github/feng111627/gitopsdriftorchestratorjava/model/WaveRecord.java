package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.util.List;

/**
 * 一个同步波次的执行记录。
 */
public record WaveRecord(
        int wave,
        List<String> resourceIds,
        WaveState state,
        String detail
) {
}
