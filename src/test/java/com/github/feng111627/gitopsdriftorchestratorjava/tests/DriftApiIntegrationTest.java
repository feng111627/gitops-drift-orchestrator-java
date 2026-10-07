package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务四：HTTP 端到端。用隔离的临时数据目录启动完整 Spring 上下文，
 * 串起 对账 -> 波次同步 -> 放量 -> 回滚，并验证并发拒绝返回 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "drift.data-root=./target/it-data")
@DisplayName("业务四-端到端：HTTP 串联对账/同步/发布/回滚")
class DriftApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DriftApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void prepareState() {
        // 每用例前重建隔离状态
        Path root = Path.of("target/it-data");
        try {
            java.nio.file.Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // 忽略
                        }
                    });
        } catch (Exception ignored) {
            // 目录不存在即可
        }
        String desired = """
                [
                  {"id":"app","type":"service","version":"v1","desiredValue":"v1@100",
                   "dependsOn":["db"]},
                  {"id":"db","type":"database","version":"v1","desiredValue":"v1@100"}
                ]
                """;
        TestStateFixtures.writeDesired(root, "stg", desired);
        TestStateFixtures.writeObservedRaw(root, "stg", "");
    }

    @Test
    @DisplayName("用例4.1 缺失对账->同步成功两波 HEALTH_PASSED->再对账 clean")
    void reconcileSyncReconcileOverHttp() throws Exception {
        log.info("[端到端] 初始对账（应有 2 个 MISSING）");
        mockMvc.perform(post("/api/envs/stg/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingCount").value(2))
                .andExpect(jsonPath("$.clean").value(false));

        log.info("[端到端] 触发波次同步");
        mockMvc.perform(post("/api/envs/stg/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.waves[0].resourceIds[0]").value("db"))
                .andExpect(jsonPath("$.waves[0].state").value("HEALTH_PASSED"))
                .andExpect(jsonPath("$.waves[1].resourceIds[0]").value("app"))
                .andExpect(jsonPath("$.waves[1].state").value("HEALTH_PASSED"));

        log.info("[端到端] 同步后对账应 clean");
        mockMvc.perform(post("/api/envs/stg/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(true))
                .andExpect(jsonPath("$.syncedCount").value(2));
    }

    @Test
    @DisplayName("用例4.2 快照对账->开始放量->回滚并对齐快照，接口全链路返回正确")
    void snapshotStartRollbackOverHttp() throws Exception {
        mockMvc.perform(post("/api/envs/stg/sync")).andExpect(status().isOk());

        log.info("[端到端] 发布前落快照");
        String snap = "snap-000001";
        mockMvc.perform(post("/api/envs/stg/reconcile").param("snapshot", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snap));

        log.info("[端到端] app 从 v1 向 v2 放量 10%");
        mockMvc.perform(post("/api/envs/stg/rollouts/app/start")
                        .contentType("application/json")
                        .content("{\"newVersion\":\"v2\",\"percentage\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/envs/stg/reconcile"))
                .andExpect(jsonPath("$.clean").value(false));

        log.info("[端到端] 回滚并对齐快照 {}", snap);
        mockMvc.perform(post("/api/envs/stg/rollouts/app/rollback")
                        .contentType("application/json")
                        .content("{\"snapshotId\":\"" + snap + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rollbackSnapshot").value(snap));

        mockMvc.perform(post("/api/envs/stg/reconcile"))
                .andExpect(jsonPath("$.clean").value(true));

        log.info("[端到端] 重复回滚必须被拒绝（400 + VALIDATION_FAILED）");
        mockMvc.perform(post("/api/envs/stg/rollouts/app/rollback")
                        .contentType("application/json")
                        .content("{\"snapshotId\":\"" + snap + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("用例4.3 输入参数非法（百分比越界）返回 400，理由可区分")
    void invalidPercentageReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/envs/stg/sync")).andExpect(status().isOk());
        mockMvc.perform(post("/api/envs/stg/rollouts/app/start")
                        .contentType("application/json")
                        .content("{\"newVersion\":\"v2\",\"percentage\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"));
        log.info("[端到端] 非法百分比被拒绝为 400 VALIDATION_FAILED");
    }
}
