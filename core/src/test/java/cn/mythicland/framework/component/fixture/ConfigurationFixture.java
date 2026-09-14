package cn.mythicland.framework.component.fixture;

import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.LoadOrder;
import cn.mythicland.framework.component.LoadPhase;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.util.ArrayList;
import java.util.List;

@InjectComponent
@LoadOrder(phase = LoadPhase.CONFIGURATION)
public final class ConfigurationFixture implements ManagedLifecycle {
    public static final List<String> EVENTS = new ArrayList<>();

    @Override
    public void load() {
        EVENTS.add("configuration.load");
    }

    @Override
    public void unload() {
        EVENTS.add("configuration.unload");
    }
}
