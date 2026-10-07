package com.github.feng111627.gitopsdriftorchestratorjava.model;

/** 资源在同一环境内的唯一标识：类型 + 名称。 */
public record ResourceId(String type, String name) implements Comparable<ResourceId> {

    public ResourceId {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("资源类型不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("资源名称不能为空");
        }
    }

    /** "type/name" 形式解析；非法格式抛 IllegalArgumentException。 */
    public static ResourceId parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("资源标识为 null");
        }
        int idx = raw.indexOf('/');
        if (idx <= 0 || idx == raw.length() - 1 || raw.indexOf('/', idx + 1) >= 0) {
            throw new IllegalArgumentException("资源标识格式非法，应为 type/name：" + raw);
        }
        return new ResourceId(raw.substring(0, idx), raw.substring(idx + 1));
    }

    @Override
    public String toString() {
        return type + "/" + name;
    }

    @Override
    public int compareTo(ResourceId o) {
        int c = type.compareTo(o.type);
        return c != 0 ? c : name.compareTo(o.name);
    }
}
