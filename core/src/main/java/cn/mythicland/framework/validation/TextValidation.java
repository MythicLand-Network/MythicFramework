package cn.mythicland.framework.validation;

import java.util.Objects;

/** Common validation for identifiers and other required text. */
public final class TextValidation {
    private TextValidation() {
    }

    /**
     * Trims and validates required text.
     *
     * @param value source text
     * @param fieldName name used in errors
     * @return nonblank, trimmed text
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if the trimmed text is blank
     */
    public static String requireText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName).trim();
        if (text.isBlank()) throw new IllegalArgumentException(fieldName + " cannot be blank");
        return text;
    }
}
