package cn.mythicland.framework.bukkit.diagnostics;

import cn.mythicland.framework.diagnostics.DiagnosticRecorder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.EnumProtocol;
import net.minecraft.server.v1_8_R3.EnumProtocolDirection;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Items;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketCompressor;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketDecompressor;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardScore;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticWireTest {
    @TempDir
    Path directory;

    @Test
    void wire_shouldPreserveBufferAndFilterTargetsAcrossPacketGroups() throws Exception {
        DispenserRegistry.c();
        var recorder = this.recorder();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        var wire = this.wire(recorder);
        wire.position(channel.pipeline(), "wire");
        ByteBuf equipment = this.encode(new PacketPlayOutEntityEquipment(887, 4, new ItemStack(Items.IRON_HELMET)));
        int reader = equipment.readerIndex();
        int writer = equipment.writerIndex();
        assertTrue(channel.writeOutbound(equipment));
        assertSame(equipment, channel.readOutbound());
        assertEquals(reader, equipment.readerIndex());
        assertEquals(writer, equipment.writerIndex());
        assertEquals(1, equipment.refCnt());
        equipment.release();
        for (Packet packet : List.of(new PacketPlayOutEntityEquipment(923, 4, null),
                new PacketPlayOutScoreboardScore("Target"), new PacketPlayOutScoreboardScore("Other"))) {
            channel.writeOutbound(this.encode(packet));
            ((ByteBuf) channel.readOutbound()).release();
        }
        ByteBuf chat = Unpooled.wrappedBuffer(new byte[]{2});
        channel.writeOutbound(chat);
        assertSame(chat, channel.readOutbound());
        chat.release();
        channel.finish();
        var records = this.records(recorder);
        assertEquals(2, records.size());
        var fields = records.getFirst().getAsJsonObject("fields");
        assertEquals("IRON_HELMET", fields.getAsJsonObject("payload").getAsJsonObject("item").get("type").getAsString());
        assertEquals("test-connection", fields.get("connection").getAsString());
        assertEquals(64, fields.get("sha256").getAsString().length());
        assertEquals("Target", records.getLast().getAsJsonObject("fields").getAsJsonObject("payload").get("entry").getAsString());
    }

    @Test
    void wire_shouldObserveRewrittenBytesBeforeCompressionIncludingAfterCompressionReinstallation() throws Exception {
        DispenserRegistry.c();
        var recorder = this.recorder();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("compress", new PacketCompressor(0));
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                ByteBuf bytes = (ByteBuf) message;
                bytes.setShort(5, 310);
                context.write(message, promise);
            }
        });
        var wire = this.wire(recorder);
        for (int attempt = 0; attempt < 2; attempt++) {
            wire.position(channel.pipeline(), "wire");
            channel.writeOutbound(this.encode(new PacketPlayOutEntityEquipment(887, 4, new ItemStack(Items.IRON_HELMET))));
            EmbeddedChannel decoder = new EmbeddedChannel(new PacketDecompressor(0));
            decoder.writeInbound(channel.readOutbound());
            ByteBuf decoded = (ByteBuf) decoder.readInbound();
            assertEquals(310, decoded.getShort(5));
            decoded.release();
            decoder.finish();
            channel.pipeline().remove("compress");
            channel.pipeline().addBefore("encoder", "compress", new PacketCompressor(0));
        }
        channel.finish();
        var records = this.records(recorder);
        assertEquals(2, records.size());
        for (var record : records)
            assertEquals("DIAMOND_HELMET", record.getAsJsonObject("fields").getAsJsonObject("payload")
                    .getAsJsonObject("item").get("type").getAsString());
    }

    @Test
    void malformedSelectedPacket_shouldStopDiagnosticsWithoutChangingDelivery() throws Exception {
        var recorder = this.recorder();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        this.wire(recorder).position(channel.pipeline(), "wire");
        ByteBuf bytes = Unpooled.wrappedBuffer(new byte[]{4, (byte) 128});
        channel.writeOutbound(bytes);
        assertSame(bytes, channel.readOutbound());
        assertEquals(0, bytes.readerIndex());
        assertEquals(1, bytes.refCnt());
        bytes.release();
        channel.finish();
        assertEquals("capture_error", recorder.completion().get(3, TimeUnit.SECONDS).reason());
    }

    private DiagnosticWire wire(DiagnosticRecorder recorder) {
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getName()).thenReturn("Target");
        when(target.getEntityId()).thenReturn(887);
        var options = new DiagnosticOptions(Set.of(DiagnosticOptions.PacketGroup.EQUIPMENT,
                DiagnosticOptions.PacketGroup.HEALTH), Set.of(), 60, 20);
        return new DiagnosticWire(recorder, UUID.randomUUID(), "test-connection", new DiagnosticPacketSelection(options, List.of(target)), options);
    }

    private ByteBuf encode(Packet packet) throws Exception {
        PacketDataSerializer bytes = new PacketDataSerializer(Unpooled.buffer());
        bytes.b(EnumProtocol.PLAY.a(EnumProtocolDirection.CLIENTBOUND, packet));
        packet.b(bytes);
        return bytes;
    }

    private DiagnosticRecorder recorder() throws Exception {
        return new DiagnosticRecorder(this.directory, new DiagnosticRecorder.Limits(
                Duration.ofMinutes(30), 8192, 1024 * 1024, 4 * 1024 * 1024, 0, 128), Map.of());
    }

    private List<JsonObject> records(DiagnosticRecorder recorder) throws Exception {
        recorder.stop("test");
        var summary = recorder.completion().get(3, TimeUnit.SECONDS);
        assertEquals("", summary.error());
        assertEquals(0, summary.dropped());
        List<JsonObject> records = new ArrayList<>();
        try (var paths = Files.list(recorder.directory())) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".jsonl.gz")).sorted().toList()) {
                try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
                    reader.lines().map(line -> new JsonParser().parse(line).getAsJsonObject())
                            .filter(record -> record.get("stage").getAsString().equals("outbound.encoded")).forEach(records::add);
                }
            }
        }
        return records;
    }
}
