package cn.mythicland.framework.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SensitiveCommandLogFilterTest {
    @Test
    void suppressesOnlySensitiveCommandExecutionRecords() {
        String logger = "command-handler";
        try (SensitiveCommandLogFilter ignored = new SensitiveCommandLogFilter(LogManager.class.getClassLoader(), logger, Set.of("premium"))) {
            Filter filter = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getLoggerConfig(logger).getFilter();
            assertEquals(Filter.Result.DENY, filter.filter(event(logger, "Player -> executed command /premium secret")));
            assertEquals(Filter.Result.DENY, filter.filter(event(logger, "Player -> executed command /mythiclogin:premium secret")));
            assertEquals(Filter.Result.NEUTRAL, filter.filter(event(logger, "Player -> executed command /premiuminfo")));
            assertEquals(Filter.Result.NEUTRAL, filter.filter(event(logger, "Player -> executed command /server lobby")));
        }
    }

    private static Log4jLogEvent event(String logger, String message) {
        return Log4jLogEvent.newBuilder().setLoggerName(logger).setMessage(new SimpleMessage(message)).build();
    }
}
