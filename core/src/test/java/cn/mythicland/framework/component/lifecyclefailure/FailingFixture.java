package cn.mythicland.framework.component.lifecyclefailure;

import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.LoadOrder;
import cn.mythicland.framework.component.LoadPhase;
import cn.mythicland.framework.component.ManagedLifecycle;

@InjectComponent
@LoadOrder(phase = LoadPhase.SERVICES)
public final class FailingFixture implements ManagedLifecycle {
    private final RollbackFixture rollbackFixture;

    public FailingFixture(RollbackFixture rollbackFixture) {
        this.rollbackFixture = rollbackFixture;
    }

    @Override
    public void load() {
        if (this.rollbackFixture == null) throw new AssertionError();
        throw new IllegalStateException("load failed");
    }
}
