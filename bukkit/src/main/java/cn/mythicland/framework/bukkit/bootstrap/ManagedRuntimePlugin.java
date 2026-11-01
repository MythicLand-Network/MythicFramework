package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.runtime.RuntimeBootstrap;

/**
 * Owns a plugin runtime while allowing platform-specific startup prerequisites.
 */
public abstract class ManagedRuntimePlugin extends ManagedPlugin {
    private RuntimeBootstrap runtime;

    @Override
    public final void onLoad() {
        if (this.prepareRuntimeOnLoad()) this.initializeRuntime();
    }

    @Override
    protected final void enablePlugin() {
        if (!this.canEnableRuntime()) return;
        this.initializeRuntime();
        this.runtime.load();
        this.runtime.enable();
    }

    @Override
    protected final void disablePlugin() {
        if (this.runtime == null) return;
        try {
            this.runtime.disable();
        } finally {
            this.runtime = null;
        }
    }

    protected final void reloadRuntime() {
        if (this.runtime != null) this.runtime.reload();
    }

    protected boolean prepareRuntimeOnLoad() {
        return false;
    }

    protected boolean canEnableRuntime() {
        return true;
    }

    protected abstract RuntimeBootstrap createRuntime();

    private void initializeRuntime() {
        if (this.runtime == null) this.runtime = this.createRuntime();
    }
}
