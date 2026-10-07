package com.github.feng111627.gitopsdriftorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.HealthChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.feng111627.gitopsdriftorchestratorjava.tests.support.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务：HTTP 接口端到端——写入期望、同步、对账、并发触发返回 409。
 * 健康门控 {@link HealthGate} 是单例，每个用例前显式重置，避免用例间串状态。
 */
@AutoConfigureMockMvc
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "app.state-dir=target/it-state", "app.health-timeout-millis=10000"
})
class ApiIntegrationTests {

    /** 可阻塞的健康检查门控：默认放行，需要复现并发时调用 block()。 */
    static class HealthGate implements HealthChecker {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile boolean blocking = false;

        void block() {
            blocking = true;
        }

        void reset() {
            blocking = false;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        CountDownLatch enteredLatch() {
            return entered;
        }

        void pass() {
            release.countDown();
        }

        @Override
        public String check(DesiredResource resource, long timeoutMillis) throws InterruptedException {
            if (blocking) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return null;
        }
    }

    @TestConfiguration
    static class FaultConfig {
        @Bean
        @Primary
        HealthGate healthGate() {
            return new HealthGate();
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private HealthGate gate;

    @BeforeEach
    void resetGate() {
        gate.reset();
    }

    @Test
    @DisplayName("端到端：写入期望 -> 同步成功 -> 对账收敛，重复对账报告 id 稳定")
    void desiredSyncReconcileFlow() throws Exception {
        DesiredResource db = desired("stateful", "db", "v1", Map.of("disk", "10Gi"));
        DesiredResource api = desired("service", "api", "v1", Map.of("replicas", 2),
                id("stateful", "db"));
        mvc.perform(put("/api/envs/it-flow/desired")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(List.of(api, db))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/envs/it-flow/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.totalWaves").value(2));

        String r1 = mvc.perform(get("/api/envs/it-flow/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("SYNCED"))
                .andReturn().getResponse().getContentAsString();
        String firstId = mapper.readTree(r1).get("reportId").asText();
        String r2 = mvc.perform(get("/api/envs/it-flow/reconcile"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(firstId,
                mapper.readTree(r2).get("reportId").asText());
    }

    @Test
    @DisplayName("端到端：同步进行中再次触发返回 409 CONCURRENT_SYNC")
    void concurrentSyncReturns409() throws Exception {
        DesiredResource db = desired("stateful", "slowdb", "v1", Map.of("disk", "1Gi"));
        mvc.perform(put("/api/envs/it-lock/desired")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(List.of(db))))
                .andExpect(status().isOk());

        gate.block();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> mvc.perform(post("/api/envs/it-lock/sync")));
            assertTrue(gate.enteredLatch().await(5, TimeUnit.SECONDS),
                    "第一个同步应已进入健康检查");

            mvc.perform(post("/api/envs/it-lock/sync"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_SYNC"));
        } finally {
            gate.pass();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("端到端：坏观测条目通过对账接口标 INVALID，不影响接口成功返回")
    void invalidObservedReported() throws Exception {
        Path stateDir = Path.of("target/it-state/it-bad");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("observed.json"),
                "[{\"type\":\"service\",\"name\":\"oops\",\"spec\":\"broken\"}]");

        mvc.perform(get("/api/envs/it-bad/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("INVALID"))
                .andExpect(jsonPath("$.items[0].detail").exists());
    }
}
