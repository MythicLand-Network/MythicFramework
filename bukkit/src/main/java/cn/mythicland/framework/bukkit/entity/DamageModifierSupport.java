package cn.mythicland.framework.bukkit.entity;

import com.google.common.base.Function;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Replaces one legacy Bukkit reduction while retaining the native downstream damage functions.
 */
@SuppressWarnings("deprecation")
public final class DamageModifierSupport {

    private static final Field FUNCTIONS = resolveFunctions();

    private DamageModifierSupport() {
    }

    private static Field resolveFunctions() {
        try {
            Field field = EntityDamageEvent.class.getDeclaredField("modifierFunctions");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * Sets an authoritative base amount and rebuilds native modifiers from their functions.
     * This also repairs events whose BASE was changed directly without updating dependent values.
     * Call before replacing equipment reductions, on the server thread.
     *
     * @param event  current event
     * @param damage finite, non-negative base damage
     */
    @SuppressWarnings("unchecked")
    public static void setBaseDamage(EntityDamageEvent event, double damage) {
        if (!Double.isFinite(damage) || damage < 0) {
            throw new IllegalArgumentException("Base damage must be finite and non-negative");
        }
        try {
            Map<DamageModifier, Function<Double, Double>> functions =
                    (Map<DamageModifier, Function<Double, Double>>) FUNCTIONS.get(event);
            Map<DamageModifier, Double> values = new EnumMap<>(DamageModifier.class);
            values.put(DamageModifier.BASE, damage);
            double remaining = damage;
            for (DamageModifier step : DamageModifier.values()) {
                if (step == DamageModifier.BASE || !event.isApplicable(step)) continue;
                double value = functions.get(step).apply(remaining);
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid damage modifier: " + step);
                values.put(step, value);
                remaining += value;
            }
            values.forEach(event::setDamage);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access Bukkit damage modifier functions", exception);
        }
    }

    /**
     * Replaces a non-base reduction and recalculates later modifiers, including absorption.
     * Existing numeric adjustments to other modifiers are retained. The replacement remains active
     * when a later listener changes the event's base damage. Call on the server thread.
     *
     * @param event           the current damage event
     * @param modifier        an applicable modifier other than BASE
     * @param remainingDamage maps incoming damage to damage remaining after this modifier
     * @throws IllegalArgumentException if the modifier or computed values are invalid
     * @throws IllegalStateException    if Bukkit's modifier-function contract is inaccessible
     */
    @SuppressWarnings("unchecked")
    public static void replaceReduction(
            EntityDamageEvent event,
            DamageModifier modifier,
            DoubleUnaryOperator remainingDamage
    ) {
        replaceReductions(event, Map.of(modifier, remainingDamage));
    }

    /**
     * Replaces several equipment reductions in one pass while preserving potion and absorption
     * functions. All rules must return finite remaining damage and target applicable modifiers.
     *
     * @param event      current server-thread event
     * @param reductions modifier-to-remaining-damage rules
     */
    @SuppressWarnings("unchecked")
    public static void replaceReductions(
            EntityDamageEvent event,
            Map<DamageModifier, DoubleUnaryOperator> reductions
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(reductions, "reductions");
        reductions.forEach((modifier, rule) -> {
            Objects.requireNonNull(rule, "rule");
            if (modifier == DamageModifier.BASE || !event.isApplicable(modifier)) {
                throw new IllegalArgumentException("Damage modifier is not replaceable: " + modifier);
            }
        });
        try {
            Map<DamageModifier, Function<Double, Double>> original =
                    (Map<DamageModifier, Function<Double, Double>>) FUNCTIONS.get(event);
            Map<DamageModifier, Function<Double, Double>> functions = new EnumMap<>(original);
            reductions.forEach((modifier, rule) ->
                    functions.put(modifier, incoming -> rule.applyAsDouble(incoming) - incoming));
            Map<DamageModifier, Double> values = new EnumMap<>(DamageModifier.class);
            double previous = event.getDamage();
            double current = previous;
            for (DamageModifier step : DamageModifier.values()) {
                if (step == DamageModifier.BASE || !event.isApplicable(step)) continue;
                double oldValue = event.getDamage(step);
                double replacement = functions.get(step).apply(current);
                if (!reductions.containsKey(step)) replacement += oldValue - original.get(step).apply(previous);
                if (!Double.isFinite(replacement)) {
                    throw new IllegalArgumentException("Damage modifier result must be finite: " + step);
                }
                values.put(step, replacement);
                previous += oldValue;
                current += replacement;
            }
            FUNCTIONS.set(event, functions);
            values.forEach(event::setDamage);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access Bukkit damage modifier functions", exception);
        }
    }
}
