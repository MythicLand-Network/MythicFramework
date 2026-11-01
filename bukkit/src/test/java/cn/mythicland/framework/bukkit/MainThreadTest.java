package cn.mythicland.framework.bukkit;

import cn.mythicland.framework.bukkit.MainThread;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class MainThreadTest {
    @Test
    void acceptsMainThreadAndReportsTheCallingOperationOtherwise() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertDoesNotThrow(() -> MainThread.require("operation"));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertEquals("operation", assertThrows(IllegalStateException.class,
                    () -> MainThread.require("operation")).getMessage());
        }
    }
}
