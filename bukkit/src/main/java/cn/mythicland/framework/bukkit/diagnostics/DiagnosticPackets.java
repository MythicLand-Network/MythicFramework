package cn.mythicland.framework.bukkit.diagnostics;

import cn.mythicland.framework.diagnostics.DiagnosticIds;
import cn.mythicland.framework.diagnostics.DiagnosticRecorder;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

final class DiagnosticPackets implements AutoCloseable {
    private static final Set<String> OUTBOUND_NAMES = Set.of(
            "PacketPlayOutEntityVelocity",
            "PacketPlayOutEntityStatus",
            "PacketPlayOutAnimation",
            "PacketPlayOutPosition",
            "PacketPlayOutExplosion",
            "PacketPlayOutEntityTeleport",
            "PacketPlayOutRelEntityMove",
            "PacketPlayOutRelEntityMoveLook",
            "PacketPlayOutEntityLook",
            "PacketPlayOutEntityHeadRotation",
            "PacketPlayOutEntityMetadata",
            "PacketPlayOutUpdateAttributes",
            "PacketPlayOutEntityEffect",
            "PacketPlayOutRemoveEntityEffect",
            "PacketPlayOutHeldItemSlot",
            "PacketPlayOutEntityEquipment",
            "PacketPlayOutKeepAlive",
            "PacketPlayOutTransaction"
    );
    private static final PacketType[] TYPES = {
            PacketType.Play.Client.USE_ENTITY, PacketType.Play.Client.ARM_ANIMATION, PacketType.Play.Client.HELD_ITEM_SLOT,
            PacketType.Play.Client.BLOCK_DIG, PacketType.Play.Client.BLOCK_PLACE, PacketType.Play.Client.ENTITY_ACTION,
            PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK, PacketType.Play.Client.LOOK,
            PacketType.Play.Client.FLYING, PacketType.Play.Client.KEEP_ALIVE, PacketType.Play.Client.TRANSACTION,
            PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.ENTITY_STATUS, PacketType.Play.Server.ANIMATION,
            PacketType.Play.Server.POSITION, PacketType.Play.Server.EXPLOSION, PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.REL_ENTITY_MOVE, PacketType.Play.Server.REL_ENTITY_MOVE_LOOK, PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.ENTITY_HEAD_ROTATION, PacketType.Play.Server.ENTITY_METADATA, PacketType.Play.Server.UPDATE_ATTRIBUTES,
            PacketType.Play.Server.ENTITY_EFFECT, PacketType.Play.Server.REMOVE_ENTITY_EFFECT, PacketType.Play.Server.HELD_ITEM_SLOT,
            PacketType.Play.Server.ENTITY_EQUIPMENT, PacketType.Play.Server.KEEP_ALIVE, PacketType.Play.Server.TRANSACTION
    };
    private final DiagnosticRecorder recorder;
    private final DiagnosticIds ids;
    private final Set<UUID> watched;
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Set<Channel> installedChannels = ConcurrentHashMap.newKeySet();
    private final List<PacketAdapter> listeners = new ArrayList<>();
    private final ThreadLocal<Long> frame = ThreadLocal.withInitial(() -> 0L);
    private final Set<Integer> entityIds = ConcurrentHashMap.newKeySet();
    private final String prefix = "mythic-debug-" + UUID.randomUUID();
    private volatile boolean closed;

    DiagnosticPackets(JavaPlugin plugin, DiagnosticRecorder recorder, DiagnosticIds ids, Set<UUID> watched) {
        this.recorder = recorder;
        this.ids = ids;
        this.watched = watched;
        try {
            for (ListenerPriority priority : new ListenerPriority[]{ListenerPriority.LOWEST, ListenerPriority.MONITOR}) {
                PacketAdapter listener = new PacketAdapter(plugin, priority, TYPES) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        capture(event, "receive", priority);
                    }

                    @Override
                    public void onPacketSending(PacketEvent event) {
                        capture(event, "send", priority);
                    }
                };
                this.listeners.add(listener);
                ProtocolLibrary.getProtocolManager().addPacketListener(listener);
            }
        } catch (RuntimeException | LinkageError failure) {
            for (PacketAdapter listener : this.listeners)
                ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            throw failure;
        }
    }

    void connect(Player player) {
        if (this.closed || !this.watched.contains(player.getUniqueId())) return;
        this.entityIds.add(player.getEntityId());
        Channel channel = ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel;
        if (!channel.isActive()) return;
        Channel previous = this.channels.put(player.getUniqueId(), channel);
        if (previous == channel) return;
        if (previous != null) this.remove(previous);
        this.installedChannels.add(channel);
        UUID uuid = player.getUniqueId();
        channel.eventLoop().execute(() -> {
            if (this.closed || !this.recorder.isRecording() || !channel.isActive()) return;
            try {
                var pipeline = channel.pipeline();
                String frameBoundary = pipeline.get("decompress") == null ? "splitter" : "decompress";
                if (pipeline.get(frameBoundary) == null || pipeline.get("decoder") == null || pipeline.get("packet_handler") == null) {
                    throw new IllegalStateException("Unsupported diagnostic pipeline: " + pipeline.names());
                }
                pipeline.addAfter(frameBoundary, this.prefix + "-frame", new DiagnosticFrames(this.recorder, uuid, this.frame));
                pipeline.addAfter("decoder", this.prefix + "-decoded", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        decoded(uuid, channel, message, "decoded.packet");
                        context.fireChannelRead(message);
                    }
                });
                pipeline.addBefore("packet_handler", this.prefix + "-dispatch", new ChannelDuplexHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        decoded(uuid, channel, message, "dispatch.packet");
                        context.fireChannelRead(message);
                    }

                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (!closed && recorder.isRecording()) {
                            try {
                                if (relevant(message, true)) {
                                    long id = ids.id(message);
                                    long transmission = recorder.nextId();
                                    Map<String, Object> fields = base(uuid, channel);
                                    fields.put("packetId", id);
                                    fields.put("transmissionId", transmission);
                                    fields.put("type", message.getClass().getName());
                                    fields.put("payload", DiagnosticSnapshot.value(message));
                                    recorder.record("outbound.write", fields);
                                    promise.addListener(result -> {
                                        Map<String, Object> complete = base(uuid, channel);
                                        complete.put("packetId", id);
                                        complete.put("transmissionId", transmission);
                                        complete.put("success", result.isSuccess());
                                        complete.put("error", result.cause() == null ? null : result.cause().toString());
                                        recorder.record("outbound.complete", complete);
                                    });
                                }
                            } catch (RuntimeException failure) {
                                recorder.fail("outbound.write", failure);
                            }
                        }
                        context.write(message, promise);
                    }
                });
                this.recorder.record("pipeline.installed", Map.of("player", uuid.toString(), "connection", channel.toString(),
                        "names", List.copyOf(pipeline.names()), "frameBoundary", frameBoundary));
            } catch (RuntimeException failure) {
                this.recorder.fail("pipeline.install", failure);
            }
        });
    }

    private void decoded(UUID uuid, Channel channel, Object message, String stage) {
        if (this.closed || !this.recorder.isRecording()) return;
        try {
            if (!relevant(message, false)) return;
            Map<String, Object> fields = base(uuid, channel);
            fields.put("packetId", this.ids.id(message));
            fields.put("frameId", this.frame.get());
            fields.put("type", message.getClass().getName());
            fields.put("payload", DiagnosticSnapshot.value(message));
            this.recorder.record(stage, fields);
        } catch (RuntimeException failure) {
            this.recorder.fail(stage, failure);
        }
    }

    private void capture(PacketEvent event, String direction, ListenerPriority priority) {
        if (this.closed || !this.recorder.isRecording() || event.getPlayer() == null
                || !this.watched.contains(event.getPlayer().getUniqueId())) return;
        try {
            Object handle = event.getPacket().getHandle();
            if (!relevant(handle, direction.equals("send"))) return;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", event.getPlayer().getUniqueId().toString());
            fields.put("packetId", this.ids.id(handle));
            fields.put("eventId", this.ids.id(event));
            fields.put("frameId", this.frame.get());
            fields.put("direction", direction);
            fields.put("priority", priority.name());
            fields.put("type", event.getPacketType().name());
            fields.put("cancelled", event.isCancelled());
            fields.put("payload", DiagnosticSnapshot.value(handle));
            this.recorder.record("protocol.packet", fields);
        } catch (RuntimeException failure) {
            this.recorder.fail("protocol.packet", failure);
        }
    }

    private boolean relevant(Object packet, boolean outbound) {
        String type = packet.getClass().getSimpleName();
        if (!outbound) {
            return type.startsWith("PacketPlayIn") && (type.contains("Flying") || type.contains("Position")
                    || type.contains("Look") || type.contains("UseEntity") || type.contains("ArmAnimation")
                    || type.contains("BlockDig") || type.contains("BlockPlace") || type.contains("HeldItemSlot")
                    || type.contains("EntityAction") || type.contains("KeepAlive") || type.contains("Transaction"));
        }
        if (!OUTBOUND_NAMES.contains(type)) return false;
        if (!type.contains("Entity") || type.equals("PacketPlayOutEntityEffect")) {
            if (!type.contains("Entity")) return true;
        }
        try {
            for (Class<?> owner = packet.getClass(); owner != null; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        return this.entityIds.contains(field.getInt(packet));
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot identify outbound diagnostic entity: " + type, failure);
        }
        return false;
    }

    private static Map<String, Object> base(UUID uuid, Channel channel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player", uuid.toString());
        out.put("connection", channel.toString());
        return out;
    }

    CompletableFuture<Void> detach() {
        this.closed = true;
        for (PacketAdapter listener : this.listeners)
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
        this.listeners.clear();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Channel channel : this.installedChannels) pending.add(this.remove(channel));
        this.channels.clear();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> remove(Channel channel) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable remove = () -> {
            try {
                for (String suffix : List.of("-frame", "-decoded", "-dispatch")) {
                    if (channel.pipeline().get(this.prefix + suffix) != null)
                        channel.pipeline().remove(this.prefix + suffix);
                }
                this.installedChannels.remove(channel);
                done.complete(null);
            } catch (RuntimeException failure) {
                done.completeExceptionally(failure);
            }
        };
        if (channel.eventLoop().isShuttingDown()) remove.run();
        else {
            try {
                channel.eventLoop().execute(remove);
            } catch (RejectedExecutionException shutdown) {
                remove.run();
            }
        }
        return done;
    }

    @Override
    public void close() {
        this.detach();
    }
}
