package cn.mythicland.framework.bukkit.diagnostics;

import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.Items;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardScore;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardTeam;
import net.minecraft.server.v1_8_R3.Scoreboard;
import net.minecraft.server.v1_8_R3.ScoreboardTeam;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticPacketSelectionTest {
    @Test
    void deletedTargetTeam_shouldNotKeepMatchingWhenItsNameIsReused() {
        Player target = mock(Player.class);
        UUID observer = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getName()).thenReturn("Windyqing");
        when(target.getEntityId()).thenReturn(887);
        DiagnosticPacketSelection selection = new DiagnosticPacketSelection(new DiagnosticOptions(
                Set.of(DiagnosticOptions.PacketGroup.VISIBILITY), Set.of(), 60, 20), List.of(target));
        ScoreboardTeam team = new Scoreboard().createTeam("selected");
        PacketPlayOutScoreboardTeam join = new PacketPlayOutScoreboardTeam(team, List.of("Windyqing"), 3);
        assertTrue(selection.relevant(observer, join, true));
        selection.sent(observer, join);
        DiagnosticPacketSelection wire = selection.copy();
        PacketPlayOutScoreboardTeam removal = new PacketPlayOutScoreboardTeam(team, 1);
        assertTrue(selection.relevant(observer, removal, true));
        assertTrue(selection.relevant(observer, removal, true), "Selection must not consume a packet before later capture stages");
        selection.sent(observer, removal);
        assertTrue(wire.relevant(observer, removal, true), "Encoded-stage team state must not be consumed by the earlier write stage");
        wire.sent(observer, removal);
        assertFalse(wire.relevant(observer, new PacketPlayOutScoreboardTeam(team, 0), true));
        assertFalse(selection.relevant(observer, new PacketPlayOutScoreboardTeam(team, 0), true));
        assertFalse(selection.relevant(observer, new PacketPlayOutScoreboardTeam(team, 2), true));
    }

    @Test
    void displayCapture_shouldSelectOnlyTargetEquipmentScoresAndDestroyedIds() {
        Player target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Windyqing");
        when(target.getEntityId()).thenReturn(887);
        DiagnosticOptions options = new DiagnosticOptions(Set.of(DiagnosticOptions.PacketGroup.EQUIPMENT,
                DiagnosticOptions.PacketGroup.HEALTH), Set.of(), 60, 20);
        DiagnosticPacketSelection selection = new DiagnosticPacketSelection(options, List.of(target));

        assertTrue(selection.relevant(observer, new PacketPlayOutEntityEquipment(887, 4, null), true));
        assertFalse(selection.relevant(observer, new PacketPlayOutEntityEquipment(923, 4, null), true));
        assertTrue(selection.relevant(observer, new PacketPlayOutScoreboardScore("Windyqing"), true));
        assertFalse(selection.relevant(observer, new PacketPlayOutScoreboardScore("Other"), true));
        assertFalse(options.inbound("PacketPlayInFlying"));
        assertFalse(options.outbound("PacketPlayOutKeepAlive"));
        PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(923, 887);
        assertTrue(selection.relevant(observer, destroy, true));
        assertArrayEquals(new int[]{887}, (int[]) ((Map<?, ?>) selection.snapshot(destroy)).get("entityIds"));

        DiagnosticPacketSelection wire = selection.copy();
        when(target.getEntityId()).thenReturn(1000);
        selection.track(target);
        assertFalse(selection.relevant(observer, destroy, true), "Old entity IDs must stop matching after reconnect");
        assertTrue(selection.relevant(observer, new PacketPlayOutEntityDestroy(1000), true));
        assertFalse(wire.relevant(observer, destroy, true));
        assertTrue(wire.relevant(observer, new PacketPlayOutEntityDestroy(1000), true));
    }

    @Test
    void equipmentSnapshot_shouldKeepMaterialWithoutExpandingNativeItemAndOnlyIncludeRequestedNbt() {
        DispenserRegistry.c();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("fixture", "helmet identity");
        helmet.setTag(tag);

        Map<String, Object> summary = DiagnosticSnapshot.item(helmet, false);
        assertEquals(Map.of("type", "IRON_HELMET", "amount", 1, "damage", 0), summary);
        Map<String, Object> detailed = DiagnosticSnapshot.item(helmet, true);
        assertTrue(detailed.get("nbt").toString().contains("helmet identity"));
        assertEquals(summary.get("type"), detailed.get("type"));
    }
}
