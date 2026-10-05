package cn.mythicland.framework.bukkit.bot.v1_8_R3;

public interface BotVisibilitySettings {
    double viewDistance();

    double idleSeconds();

    double maxViewerY();

    double nearbyDistance();

    double viewAngle();

    int visibilityCheckTicks();

    int maxRevealsPerTick();

    int spawnDelayTicks();

    int equipmentCheckTicks();
}
