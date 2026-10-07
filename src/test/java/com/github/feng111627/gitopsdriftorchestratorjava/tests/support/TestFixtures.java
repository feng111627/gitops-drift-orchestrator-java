package com.github.feng111627.gitopsdriftorchestratorjava.tests.support;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造期望/观测 JSON 条目的小工具。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static ResourceId id(String type, String name) {
        return new ResourceId(type, name);
    }

    public static DesiredResource desired(String type, String name, String version,
                                          Map<String, Object> spec, ResourceId... deps) {
        return new DesiredResource(id(type, name), version, spec, List.of(deps));
    }

    public static Map<String, Object> spec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 构造一条观测原始 JSON。 */
    public static Map<String, Object> observed(String type, String name, String version,
                                               Map<String, Object> spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", name);
        if (version != null) {
            m.put("version", version);
        }
        m.put("spec", spec);
        return m;
    }
}
