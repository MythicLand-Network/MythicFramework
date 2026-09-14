package cn.mythicland.framework.component;

public interface ComponentResolver {
    <T> T resolve(Class<T> type);
}
