package com.github.feng111627.gitopsdriftorchestratorjava.model;

import java.time.Instant;

/** 一次带版本变更的记录。 */
public record ReleaseRecord(String releaseId, String environment, ResourceId resource,
                            String version, String previousVersion, int percentage,
                            ReleaseState state, Instant createdAt, Instant updatedAt,
                            String rollbackBaselineReportId) {
}
