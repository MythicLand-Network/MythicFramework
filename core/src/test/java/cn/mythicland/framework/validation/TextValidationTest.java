package cn.mythicland.framework.validation;

import cn.mythicland.framework.validation.TextValidation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextValidationTest {
    @Test
    void trimsTextAndReportsInvalidValues() {
        assertEquals("value", TextValidation.requireText(" value ", "field"));
        assertEquals("field", assertThrows(NullPointerException.class,
                () -> TextValidation.requireText(null, "field")).getMessage());
        assertEquals("field cannot be blank", assertThrows(IllegalArgumentException.class,
                () -> TextValidation.requireText("  ", "field")).getMessage());
    }
}
