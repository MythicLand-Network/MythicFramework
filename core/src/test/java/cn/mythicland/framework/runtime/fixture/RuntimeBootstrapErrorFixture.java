package cn.mythicland.framework.runtime.fixture;

public final class RuntimeBootstrapErrorFixture {
    static {
        failInitialization();
    }

    private RuntimeBootstrapErrorFixture() {
    }

    private static void failInitialization() {
        throw new AssertionError("fixture initialization failure");
    }
}
