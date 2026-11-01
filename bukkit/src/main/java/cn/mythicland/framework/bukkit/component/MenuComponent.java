package cn.mythicland.framework.bukkit.component;

import cn.mythicland.framework.bukkit.menu.AbstractMenu;
import cn.mythicland.framework.component.ComponentRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ComponentRole(contract = AbstractMenu.class)
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MenuComponent {
}
