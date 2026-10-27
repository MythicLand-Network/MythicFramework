package cn.mythicland.framework.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRouterTest {
    @Test
    void routesAndSuggestionsRespectPermissionsAndArgumentCounts() {
        List<String> calls = new ArrayList<>();
        CommandRouter<Boolean> router = new CommandRouter<Boolean>()
                .route("send", 1, allowed -> allowed, (allowed, args) -> calls.add(args[1]))
                .route("accept", 1, allowed -> true, (allowed, args) -> calls.add(args[1]));
        assertFalse(router.execute(false, new String[]{"send", "target"}));
        assertFalse(router.execute(true, new String[]{"send"}));
        assertTrue(router.execute(true, new String[]{"SeNd", "target"}));
        assertEquals(List.of("target"), calls);
        assertEquals(List.of(), router.suggest(false, "s"));
        assertEquals(List.of("send"), router.suggest(true, "S"));
    }
}
