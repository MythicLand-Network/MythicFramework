package cn.mythicland.framework.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredTextTest {
    @Test
    void shouldRenderCallerDefinedHeadingsIndentationAndListMarkers() {
        List<String> lines = new StructuredText("    ", "")
                .line("Results")
                .blank()
                .indented("Condition:")
                .list("- ", "First", "Second")
                .build();
        assertEquals(List.of("Results", "", "    Condition:", "    - First", "    - Second"), lines);
    }

    @Test
    void builtTextShouldBeAnImmutableSnapshot() {
        StructuredText text = new StructuredText("  ", " ").line("First");
        List<String> snapshot = text.build();
        text.line("Second");
        assertEquals(List.of("First"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("Third"));
    }
}
