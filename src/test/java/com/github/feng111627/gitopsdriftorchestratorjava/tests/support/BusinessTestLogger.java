package com.github.feng111627.gitopsdriftorchestratorjava.tests.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试执行监听器：以统一格式打印“业务场景 → 用例 → 结果”，
 * 让 mvn test 的日志能直接看出覆盖了哪些业务。
 */
public class BusinessTestLogger implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    private final ThreadLocal<String> currentClass = new ThreadLocal<>();
    private final ThreadLocal<AtomicInteger> index = ThreadLocal.withInitial(AtomicInteger::new);

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            String parent = id.getParentId().orElse("");
            if (!parent.equals(currentClass.get())) {
                currentClass.set(parent);
                index.set(new AtomicInteger());
            }
            log.info("【业务用例 {}.{}】{}",
                    simpleClass(id), index.get().incrementAndGet(), displayName(id));
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
            String verdict = switch (result.getStatus()) {
                case SUCCESSFUL -> "通过 ✓";
                case FAILED -> "失败 ✗ (" + safeMessage(result) + ")";
                case ABORTED -> "中止 ⊘";
            };
            log.info("【用例结果】{} => {}", displayName(id), verdict);
        }
    }

    private String simpleClass(TestIdentifier id) {
        String uniqueId = id.getUniqueId();
        int marker = uniqueId.indexOf("[engine:junit-jupiter]/[class:");
        if (marker >= 0) {
            String rest = uniqueId.substring(marker + "[engine:junit-jupiter]/[class:".length());
            int bracket = rest.indexOf(']');
            String fqcn = bracket > 0 ? rest.substring(0, bracket) : rest;
            int dot = fqcn.lastIndexOf('.');
            return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        }
        return "?";
    }

    private String displayName(TestIdentifier id) {
        return id.getDisplayName();
    }

    private String safeMessage(TestExecutionResult result) {
        return result.getThrowable().map(Throwable::getMessage).orElse("无异常信息");
    }
}
