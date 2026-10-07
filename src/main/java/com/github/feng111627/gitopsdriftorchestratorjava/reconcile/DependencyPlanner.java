package com.github.feng111627.gitopsdriftorchestratorjava.reconcile;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceId;
import com.github.feng111627.gitopsdriftorchestratorjava.model.SyncWave;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 依据 dependsOn 做 Kahn 分层，得到同步波次。
 *
 * <p>约定：
 * <ul>
 *   <li>第 0 波是无依赖资源；某资源只依赖已落入更早波次的资源才可入当前波。</li>
 *   <li>同一波内按 id 排序，结论对声明顺序不敏感（稳定）。</li>
 *   <li>依赖指向未声明资源、依赖自己、依赖成环，一律 {@link ValidationException}，
 *       同步不会开始。</li>
 * </ul>
 */
public class DependencyPlanner {

    public static List<SyncWave> planWaves(List<DesiredResource> desired) {
        Set<ResourceId> all = validateAndIndex(desired);

        Map<ResourceId, Set<ResourceId>> remainingDeps = new LinkedHashMap<>();
        for (DesiredResource d : desired) {
            remainingDeps.put(d.id(), new HashSet<>(d.dependsOn()));
        }

        List<SyncWave> waves = new ArrayList<>();
        Set<ResourceId> placed = new HashSet<>();
        int index = 0;
        while (placed.size() < all.size()) {
            Set<ResourceId> ready = new TreeSet<>();
            for (DesiredResource d : desired) {
                if (!placed.contains(d.id()) && remainingDeps.get(d.id()).isEmpty()) {
                    ready.add(d.id());
                }
            }
            if (ready.isEmpty()) {
                Set<ResourceId> stuck = new TreeSet<>(all);
                stuck.removeAll(placed);
                throw new ValidationException("依赖成环，无法安排波次的资源：" + stuck);
            }
            waves.add(new SyncWave(index, new ArrayList<>(ready)));
            placed.addAll(ready);
            for (ResourceId r : ready) {
                for (Set<ResourceId> deps : remainingDeps.values()) {
                    deps.remove(r);
                }
            }
            index++;
        }
        return waves;
    }

    /** 校验期望声明并返回全部资源 id 集合。 */
    static Set<ResourceId> validateAndIndex(List<DesiredResource> desired) {
        Set<ResourceId> all = new HashSet<>();
        for (DesiredResource d : desired) {
            if (d.id() == null) {
                throw new ValidationException("期望状态存在没有标识的资源");
            }
            if (!all.add(d.id())) {
                throw new ValidationException("期望状态中资源重复声明：" + d.id());
            }
            if (d.dependsOn().contains(d.id())) {
                throw new ValidationException("资源不能依赖自身：" + d.id());
            }
        }
        for (DesiredResource d : desired) {
            for (ResourceId dep : d.dependsOn()) {
                if (!all.contains(dep)) {
                    throw new ValidationException(
                            "资源 " + d.id() + " 依赖的资源未在期望状态中声明：" + dep);
                }
            }
        }
        return all;
    }
}
