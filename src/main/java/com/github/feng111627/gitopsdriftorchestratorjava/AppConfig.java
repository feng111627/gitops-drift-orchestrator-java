package com.github.feng111627.gitopsdriftorchestratorjava;

import com.github.feng111627.gitopsdriftorchestratorjava.reconcile.Reconciler;
import com.github.feng111627.gitopsdriftorchestratorjava.store.FileStateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.store.StateStore;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.DefaultHealthChecker;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.HealthChecker;
import com.github.feng111627.gitopsdriftorchestratorjava.sync.SyncOrchestrator;
import com.github.feng111627.gitopsdriftorchestratorjava.release.RolloutService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 手工装配存储、对账、同步编排与发布服务，沿用 Spring Boot 现有工程。 */
@Configuration
public class AppConfig {

    @Bean
    public StateStore stateStore(@Value("${app.state-dir:state}") String stateDir) {
        return new FileStateStore(Path.of(stateDir));
    }

    @Bean
    public Reconciler reconciler(StateStore stateStore) {
        return new Reconciler(stateStore);
    }

    @Bean
    public HealthChecker healthChecker() {
        return new DefaultHealthChecker();
    }

    @Bean
    public SyncOrchestrator syncOrchestrator(StateStore stateStore, Reconciler reconciler,
                                             HealthChecker healthChecker,
                                             @Value("${app.health-timeout-millis:2000}") long timeout) {
        return new SyncOrchestrator(stateStore, reconciler, healthChecker, timeout);
    }

    @Bean
    public RolloutService rolloutService(StateStore stateStore, Reconciler reconciler) {
        return new RolloutService(stateStore, reconciler);
    }
}
