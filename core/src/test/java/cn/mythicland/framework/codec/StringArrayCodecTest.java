package cn.mythicland.framework.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringArrayCodecTest {
    @Test
    void decoding_shouldPreserveNullAndUnicodeAndRejectInvalidFrames() throws IOException {
        StringArrayCodec codec = new StringArrayCodec(1, 8, "payload");
        byte[] raw = codec.encode(null, "", "中文");
        assertArrayEquals(new String[]{null, "", "中文"}, codec.decode(raw, 3));
        assertThrows(IllegalStateException.class, () -> codec.decode(raw, 2));
        assertThrows(IllegalStateException.class, () -> new StringArrayCodec(2, 8, "payload").decode(raw, 3));
        assertThrows(IllegalStateException.class, () -> codec.decode(codec.encode("123456789"), 1));
        assertThrows(IOException.class, () -> codec.decode(Arrays.copyOf(raw, raw.length - 1), 3));
        assertThrows(IOException.class, () -> codec.decode(Arrays.copyOf(raw, raw.length + 1), 3));
    }
}
