package com.github.feng111627.gitopsdriftorchestratorjava.sync;

import com.github.feng111627.gitopsdriftorchestratorjava.model.DesiredResource;
import com.github.feng111627.gitopsdriftorchestratorjava.model.ResourceId;

/** 资源的某个依赖尚未就绪，无法继续本波。 */
public class DependencyNotReadyException extends RuntimeException {

    private final transient DesiredResource resource;
    private final transient ResourceId dependency;

    public DependencyNotReadyException(DesiredResource resource, ResourceId dependency) {
        super("资源 " + resource.id() + " 的依赖 " + dependency + " 尚未就绪");
        this.resource = resource;
        this.dependency = dependency;
    }

    public DesiredResource resource() {
        return resource;
    }

    public ResourceId dependency() {
        return dependency;
    }
}
