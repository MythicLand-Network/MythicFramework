package cn.mythicland.framework.runtime.fixture;

import cn.mythicland.framework.runtime.RuntimeBootstrapFixturePlugin;

public final class RuntimeBootstrapFixture {
    private final RuntimeBootstrapFixturePlugin plugin;
    private String state = "created";

    public RuntimeBootstrapFixture(RuntimeBootstrapFixturePlugin plugin) {
        this.plugin = plugin;
    }

    public void prepare() {
        this.state = "loaded";
    }

    public void start() {
        this.state = "enabled";
    }

    public void refresh() {
        this.state = "reloaded";
    }

    public void stop() {
        this.state = "disabled";
    }

    public String state() {
        return this.plugin == null ? "invalid" : this.state;
    }

    public ClassLoader contextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
