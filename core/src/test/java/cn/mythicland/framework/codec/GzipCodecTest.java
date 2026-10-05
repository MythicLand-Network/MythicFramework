package cn.mythicland.framework.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipCodecTest {
    @Test
    void decode_shouldReadExistingGzipDataAndRoundTripUtf8() throws Exception {
        byte[] oldPayload = Base64.getDecoder().decode("H4sIAAAAAAAAA8tIzcnJBwCGphA2BQAAAA==");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), GzipCodec.decompress(oldPayload, 8, size -> {
        }));
        byte[] text = "物品数据".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = GzipCodec.compress(text);
        assertTrue(GzipCodec.isGzip(compressed));
        assertArrayEquals(text, GzipCodec.decompress(compressed, 8, size -> {
        }));
    }

    @Test
    void decode_shouldRejectOversizedAndCorruptPayloads() throws Exception {
        byte[] compressed = GzipCodec.compress(new byte[64]);
        assertThrows(IllegalArgumentException.class, () -> GzipCodec.decompress(compressed, 0, size -> {
        }));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                GzipCodec.decompress(compressed, 8, size -> {
                    if (size > 16) throw new IllegalStateException("limit exceeded");
                })
        );
        assertEquals("limit exceeded", failure.getMessage());
        compressed[compressed.length - 8] ^= 1;
        assertThrows(IOException.class, () -> GzipCodec.decompress(compressed, 8, size -> {
        }));
    }

    @Test
    void hash_shouldRemainSha256OfUncompressedBytes() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GzipCodec.sha256("abc".getBytes(StandardCharsets.UTF_8))
        );
    }
}
