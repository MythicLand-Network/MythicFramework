package cn.mythicland.framework.bukkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BukkitRegistrationTest {
    @Test
    void closesUndoActionsInReverseOrderOnlyOnce() {
        List<String> actions = new ArrayList<>();
        BukkitRegistration registration = new BukkitRegistration();
        registration.addUndoAction(() -> actions.add("listener"));
        registration.addUndoAction(() -> actions.add("command"));

        registration.close();
        registration.close();

        assertEquals(List.of("command", "listener"), actions);
    }
}
