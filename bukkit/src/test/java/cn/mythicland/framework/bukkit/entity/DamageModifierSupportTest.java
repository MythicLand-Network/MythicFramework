package cn.mythicland.framework.bukkit.entity;

import com.google.common.base.Function;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SuppressWarnings("deprecation")
class DamageModifierSupportTest {

    @Test
    void repairsModifiersAfterAnotherListenerChangesOnlyTheBase() {
        var event = event(20);
        event.setDamage(DamageModifier.BASE, 2);
        DamageModifierSupport.setBaseDamage(event, 20);
        DamageModifierSupport.replaceReduction(event, DamageModifier.ARMOR, incoming -> incoming * 0.5);
        assertEquals(4, event.getFinalDamage(), 1e-9);
    }

    @Test
    void replacesMultipleEquipmentFunctionsTogether() {
        var event = event(20);
        DamageModifierSupport.replaceReductions(event, Map.of(
                DamageModifier.ARMOR, incoming -> incoming,
                DamageModifier.RESISTANCE, incoming -> incoming
        ));
        assertEquals(16, event.getFinalDamage(), 1e-9);
        event.setDamage(40);
        assertEquals(36, event.getFinalDamage(), 1e-9);
    }

    @Test
    void appliesCustomArmorBeforeResistanceAndAbsorptionAndSurvivesLaterBaseChanges() {
        var event = event(20);
        DamageModifierSupport.replaceReduction(event, DamageModifier.ARMOR, incoming -> incoming * 0.5);
        assertEquals(4, event.getFinalDamage(), 1e-9);
        event.setDamage(10);
        assertEquals(0, event.getFinalDamage(), 1e-9);
        event.setDamage(40);
        assertEquals(12, event.getFinalDamage(), 1e-9);
    }

    @Test
    void retainsOtherPluginsNumericAdjustmentsAndRejectsInvalidRulesWithoutPartialMutation() {
        var event = event(20);
        event.setDamage(DamageModifier.RESISTANCE, -4.2);
        DamageModifierSupport.replaceReduction(event, DamageModifier.ARMOR, incoming -> incoming * 0.5);
        assertEquals(3, event.getFinalDamage(), 1e-9);
        double before = event.getFinalDamage();
        assertThrows(IllegalArgumentException.class, () -> DamageModifierSupport.replaceReduction(
                event, DamageModifier.ARMOR, incoming -> Double.NaN));
        assertEquals(before, event.getFinalDamage());
        assertThrows(IllegalArgumentException.class,
                () -> DamageModifierSupport.replaceReduction(event, DamageModifier.BASE, incoming -> incoming));
    }

    private static EntityDamageByEntityEvent event(double damage) {
        Map<DamageModifier, Function<Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        functions.put(DamageModifier.BASE, incoming -> 0.0);
        functions.put(DamageModifier.ARMOR, incoming -> -incoming * 0.2);
        functions.put(DamageModifier.RESISTANCE, incoming -> -incoming * 0.2);
        functions.put(DamageModifier.ABSORPTION, incoming -> -Math.min(4, Math.max(0, incoming)));
        Map<DamageModifier, Double> values = new EnumMap<>(DamageModifier.class);
        values.put(DamageModifier.BASE, damage);
        double remaining = damage;
        for (DamageModifier step : DamageModifier.values()) {
            if (step == DamageModifier.BASE || !functions.containsKey(step)) continue;
            double value = functions.get(step).apply(remaining);
            values.put(step, value);
            remaining += value;
        }
        return new EntityDamageByEntityEvent(mock(Entity.class), mock(Entity.class),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, values, functions);
    }
}
