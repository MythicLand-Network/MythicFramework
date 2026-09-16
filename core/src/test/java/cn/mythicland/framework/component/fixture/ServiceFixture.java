package cn.mythicland.framework.component.fixture;

import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.LoadOrder;
import cn.mythicland.framework.component.LoadPhase;
import cn.mythicland.framework.component.ManagedLifecycle;

@InjectComponent
@LoadOrder(phase = LoadPhase.SERVICES)
public final class ServiceFixture implements ManagedLifecycle {
    private final ConfigurationFixture configuration;

    public ServiceFixture(ConfigurationFixture configuration) {
        if (!ConfigurationFixture.EVENTS.contains("configuration.load")) {
            throw new IllegalStateException("Configuration must be loaded before service construction");
        }
        this.configuration = configuration;
    }

    public ConfigurationFixture configuration() {
        return this.configuration;
    }

    @Override
    public void load() {
        ConfigurationFixture.EVENTS.add("service.load");
    }

    @Override
    public void unload() {
        ConfigurationFixture.EVENTS.add("service.unload");
    }
}
