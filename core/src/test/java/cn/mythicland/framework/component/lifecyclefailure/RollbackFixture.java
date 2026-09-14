package cn.mythicland.framework.component.lifecyclefailure;

import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.LoadOrder;
import cn.mythicland.framework.component.LoadPhase;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.util.ArrayList;
import java.util.List;

@InjectComponent
@LoadOrder(phase = LoadPhase.CONFIGURATION)
public final class RollbackFixture implements ManagedLifecycle {
    public static final List<String> EVENTS = new ArrayList<>();

    @Override
    public void load() {
        EVENTS.add("rollback.load");
    }

    @Override
    public void unload() {
        EVENTS.add("rollback.unload");
    }
}
