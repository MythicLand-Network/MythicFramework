package cn.mythicland.framework.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationPrimitivesTest {
    private static final String SECRET = "abcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    void generatesIndependentSecretsWithThirtyTwoRandomBytes() {
        String secret = SignedPayload.generateSecret();
        assertEquals(32, Base64.getUrlDecoder().decode(secret).length);
        assertNotEquals(secret, SignedPayload.generateSecret());
    }

    @Test
    void rejectsTamperedPayloadAndWrongKey() {
        byte[] original = "identity".getBytes(StandardCharsets.UTF_8);
        byte[] signed = SignedPayload.encode(original, SECRET);
        assertArrayEquals(original, SignedPayload.decode(signed, SECRET, 4096));
        assertThrows(IllegalArgumentException.class, () -> SignedPayload.decode(signed, SECRET + "x", 4096));
        signed[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SignedPayload.decode(signed, SECRET, 4096));
    }

    @Test
    void challengesAreBoundToOwnerAndCanOnlyBeConsumedOnce() {
        ChallengeRegistry<String, String> registry = new ChallengeRegistry<>();
        UUID nonce = registry.issue("connection", "identity", Duration.ofSeconds(5));
        assertTrue(registry.consume("different-connection", nonce).isEmpty());
        assertTrue(registry.consume("connection", UUID.randomUUID()).isEmpty());
        assertEquals("identity", registry.consume("connection", nonce).orElseThrow());
        assertTrue(registry.consume("connection", nonce).isEmpty());
    }

    @Test
    void expirationAndReplacementInvalidateOldChallenges() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        ChallengeRegistry<String, String> registry = new ChallengeRegistry<>(clock);
        UUID first = registry.issue("connection", "first", Duration.ofSeconds(5));
        UUID second = registry.issue("connection", "second", Duration.ofSeconds(5));
        assertTrue(registry.consume("connection", first).isEmpty());
        when(clock.millis()).thenReturn(6000L);
        assertFalse(registry.contains("connection"));
        assertTrue(registry.consume("connection", second).isEmpty());
    }

    @Test
    void supportsExistingPasswordHashesAndRejectsPremiumPlaceholder() {
        PasswordHashes hashes = new PasswordHashes();
        String password = "original-password";
        String stored = hashes.hashWithSalt(password, "1234567890abcdef");
        assertTrue(hashes.verify(password, stored));
        assertFalse(hashes.verify("different-password", stored));
        assertFalse(hashes.verify(password, "!"));
    }
}
