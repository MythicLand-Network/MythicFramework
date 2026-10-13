package cn.mythicland.framework.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationParserTest {
    @Test
    void parseMillis_shouldPreserveUnitsAndFractionalRounding() {
        String[] tokens = {"1ms", "1t", "1s", "1m", "1h", "1d"};
        long[] millis = {1, 50, 1000, 60_000, 3_600_000, 86_400_000};
        for (int index = 0; index < tokens.length; index++)
            assertEquals(millis[index], DurationParser.parseMillis(tokens[index]));
        assertEquals(1250, DurationParser.parseMillis("1.25"));
        assertEquals(90_000, DurationParser.parseMillis(" 1.5 M "));
        assertEquals(1, DurationParser.parseMillis("0.001ms"));
    }

    @Test
    void parseMillis_shouldRetainInvalidInputResult() {
        for (String token : new String[]{null, "", "0", "-1s", "seconds"})
            assertEquals(-1, DurationParser.parseMillis(token));
    }

    @Test
    void formatting_shouldChooseTheLargestExactUnitAndKeepTheAlias() {
        long[] millis = {86_400_000, 3_600_000, 60_000, 1000, 50, 51};
        String[] tokens = {"1d", "1h", "1m", "1s", "1t", "51ms"};
        for (int index = 0; index < millis.length; index++) {
            assertEquals(tokens[index], DurationParser.formatDurationToken(millis[index]));
            assertEquals(tokens[index], DurationParser.formatMillis(millis[index]));
        }
    }
}
