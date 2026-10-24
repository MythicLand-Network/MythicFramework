package cn.mythicland.framework.bukkit.diagnostics;

import cn.mythicland.framework.diagnostics.DiagnosticRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticFramesTest {
    @TempDir
    Path directory;

    @Test
    void frameObserver_shouldForwardOriginalBufferWithoutConsumingOrReleasingIt() throws Exception {
        DiagnosticRecorder recorder = this.recorder();
        ThreadLocal<Long> frame = ThreadLocal.withInitial(() -> 0L);
        AtomicLong observed = new AtomicLong();
        EmbeddedChannel channel = new EmbeddedChannel(new DiagnosticFrames(recorder, UUID.randomUUID(), frame, DiagnosticOptions.defaults(1800)),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext context, Object message) {
                        observed.set(frame.get());
                        context.fireChannelRead(message);
                    }
                });
        ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{2, (byte) 172, 2, 1});
        assertTrue(channel.writeInbound(buffer));
        assertSame(buffer, channel.readInbound());
        assertEquals(0, buffer.readerIndex());
        assertEquals(1, buffer.refCnt());
        assertTrue(observed.get() > 0);
        assertEquals(0L, frame.get());
        buffer.release();
        channel.finish();
        recorder.stop("test");
        assertEquals(0, recorder.completion().get(3, TimeUnit.SECONDS).dropped());
    }

    @Test
    void malformedFrame_shouldStopCaptureButStillForwardOriginalMessageOnce() throws Exception {
        DiagnosticRecorder recorder = this.recorder();
        EmbeddedChannel channel = new EmbeddedChannel(new DiagnosticFrames(recorder, UUID.randomUUID(),
                ThreadLocal.withInitial(() -> 0L), DiagnosticOptions.defaults(1800)));
        ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{2, (byte) 128});
        assertTrue(channel.writeInbound(buffer));
        assertSame(buffer, channel.readInbound());
        assertNull(channel.readInbound());
        assertEquals(0, buffer.readerIndex());
        assertEquals(1, buffer.refCnt());
        buffer.release();
        channel.finish();
        DiagnosticRecorder.Summary summary = recorder.completion().get(3, TimeUnit.SECONDS);
        assertEquals("capture_error", summary.reason());
        assertTrue(summary.error().contains("VarInt"));
    }

    private DiagnosticRecorder recorder() throws Exception {
        return new DiagnosticRecorder(this.directory, new DiagnosticRecorder.Limits(
                Duration.ofMinutes(30),
                8192,
                1024 * 1024,
                4 * 1024 * 1024,
                0,
                128
        ), Map.of());
    }
}
