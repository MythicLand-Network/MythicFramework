package cn.mythicland.framework.bukkit.bot.v1_8_R3;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;

public abstract class BotEntity extends EntityPlayer {
    private static final IChatBaseComponent TAB_NAME = new ChatComponentText("");
    private static final Field WORLD_ACCESSES = field(World.class, "u");
    private static final Field PLAYER_STATISTICS = field(PlayerList.class, "o");
    private final boolean walking;

    protected BotEntity(Location location, GameProfile profile, boolean walking) {
        super(MinecraftServer.getServer(), ((CraftWorld) location.getWorld()).getHandle(),
                profile, new PlayerInteractManager(((CraftWorld) location.getWorld()).getHandle()));
        releaseStatistics(server.getPlayerList(), getUniqueID(), super.getStatisticManager());
        this.walking = walking;
        NetworkManager network = new NetworkManager(EnumProtocolDirection.CLIENTBOUND);
        this.playerConnection = new PlayerConnection(server, network, this) {
            @Override
            public void sendPacket(Packet packet) {
            }
        };
        network.a(playerConnection);
        this.playerInteractManager.setGameMode(WorldSettings.EnumGamemode.SURVIVAL);
        this.invulnerableTicks = 0;
        this.joining = false;
        this.getDataWatcher().watch(10, (byte) 127);
        this.setSprinting(!walking);
        this.setPositionRotation(location.getX(), location.getY(), location.getZ(), 0, 0);
    }

    protected final void spawn() {
        if (!addUntracked()) {
            throw new IllegalStateException("Cannot add bot entity at " + getBukkitEntity().getLocation());
        }
        world.players.remove(this);
        ((WorldServer) world).getTracker().untrackEntity(this);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access bot runtime field " + type.getName() + "." + name, failure);
        }
    }

    static void releaseStatistics(PlayerList players, UUID id, ServerStatisticManager statistics) {
        try {
            @SuppressWarnings("unchecked")
            Map<UUID, ServerStatisticManager> cached =
                    (Map<UUID, ServerStatisticManager>) PLAYER_STATISTICS.get(players);
            cached.remove(id, statistics);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot release server statistics for bot " + id, failure);
        }
    }

    private boolean addUntracked() {
        try {
            @SuppressWarnings("unchecked")
            List<IWorldAccess> accesses = (List<IWorldAccess>) WORLD_ACCESSES.get(world);
            Map<Integer, IWorldAccess> replaced = new HashMap<>();
            for (int index = 0; index < accesses.size(); index++) {
                IWorldAccess access = accesses.get(index);
                if (!(access instanceof WorldManager)) continue;
                replaced.put(index, access);
                accesses.set(index, (IWorldAccess) Proxy.newProxyInstance(IWorldAccess.class.getClassLoader(),
                        new Class<?>[]{IWorldAccess.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("a") && arguments != null && arguments.length == 1 &&
                                    arguments[0] == this) return null;
                            try {return method.invoke(access, arguments);} catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }));
            }
            try {return world.addEntity(this, CreatureSpawnEvent.SpawnReason.CUSTOM);} finally {
                replaced.forEach(accesses::set);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot register untracked bot entity", failure);
        }
    }

    @Override
    public IChatBaseComponent getPlayerListName() {
        return TAB_NAME;
    }

    @Override
    public void t_() {
    }

    @Override
    public void l() {
    }

    @Override
    public void a(Statistic statistic, int amount) {
    }

    public void tickState() {
        if (noDamageTicks > 0) noDamageTicks--;
        if (invulnerableTicks > 0) invulnerableTicks--;
        super.K();
    }

    @Override
    public boolean ad() {
        return !dead;
    }

    @Override
    public void collide(Entity entity) {
        if (collidesWithEntities) super.collide(entity);
    }

    public void step(float strafe, float forward, double speed, boolean jump) {
        if (dead) return;
        updateSprinting();
        AttributeInstance movement = getAttributeInstance(GenericAttributes.MOVEMENT_SPEED);
        movement.setValue(0.1D * speed * (getBukkitEntity().getWalkSpeed() / 0.2D));
        k((float) movement.getValue());
        if (jump && onGround) bF();
        g(strafe, forward);
        aI = yaw;
        aK = yaw;
        f(yaw);
        world.players.remove(this);
    }

    @Override
    public void bF() {
        updateSprinting();
        super.bF();
    }

    public void hit(org.bukkit.entity.Entity target) {
        bw();
        attack(((CraftEntity) target).getHandle());
        updateSprinting();
    }

    private void updateSprinting() {
        if (isSprinting() == walking) setSprinting(!walking);
    }

    @Override
    protected void a(double distance, boolean ground, Block block, BlockPosition position) {
        fallDistance = 0;
    }

    @Override
    public void e(float distance, float multiplier) {
        fallDistance = 0;
    }

    @Override
    public abstract void die(DamageSource source);

    public void transfer(Location location) {
        destroy();
        WorldServer target = ((CraftWorld) location.getWorld()).getHandle();
        spawnIn(target);
        dimension = target.worldProvider.getDimension();
        playerInteractManager.a(target);
        dead = false;
        motX = motY = motZ = 0;
        setPositionRotation(location.getX(), location.getY(), location.getZ(), yaw, pitch);
        if (!addUntracked()) throw new IllegalStateException("Cannot transfer bot entity to " + location);
        world.players.remove(this);
        target.getTracker().untrackEntity(this);
    }

    public void destroy() {
        world.removeEntity(this);
        ((WorldServer) world).getTracker().untrackEntity(this);
    }
}
