package cn.mythicland.framework.bukkit.luckperms;

import cn.mythicland.framework.text.PlayerNameStyle;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class LuckPermsNameStyles {
    private final LuckPerms luckPerms;

    public LuckPermsNameStyles(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    public PlayerNameStyle resolve(
            Player player,
            String group,
            Supplier<PlayerNameStyle> memberStyle
    ) {
        return this.resolve(player, List.of("group." + group), memberStyle);
    }

    public PlayerNameStyle resolve(
            Player player,
            List<String> permissions,
            Supplier<PlayerNameStyle> memberStyle
    ) {
        User user = this.luckPerms.getPlayerAdapter(Player.class).getUser(player);
        QueryOptions options = this.luckPerms.getContextManager().getQueryOptions(player);
        return this.permissionStyle(user, options, permissions).orElseGet(memberStyle);
    }

    public CompletableFuture<PlayerNameStyle> lookup(
            UUID playerId,
            String group,
            Supplier<CompletableFuture<PlayerNameStyle>> memberStyle
    ) {
        return this.lookup(playerId, List.of("group." + group), memberStyle);
    }

    public CompletableFuture<PlayerNameStyle> lookup(
            UUID playerId,
            List<String> permissions,
            Supplier<CompletableFuture<PlayerNameStyle>> memberStyle
    ) {
        User cached = this.luckPerms.getUserManager().getUser(playerId);
        CompletableFuture<User> loaded = cached == null ? this.luckPerms.getUserManager().loadUser(playerId)
                : CompletableFuture.completedFuture(cached);
        return loaded.thenCompose(user ->
                this.permissionStyle(user, user.getQueryOptions(), permissions)
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(memberStyle)
        );
    }

    public Optional<PlayerNameStyle> groupStyle(User user, QueryOptions options, String group) {
        return this.permissionStyle(user, options, List.of("group." + group));
    }

    Optional<PlayerNameStyle> permissionStyle(User user, QueryOptions options, List<String> permissions) {
        Collection<Group> groups = null;
        for (String permission : permissions) {
            if (permission.startsWith("group.")) {
                if (groups == null) groups = user.getInheritedGroups(options);
                String group = permission.substring("group.".length());
                if (groups.stream().noneMatch(inherited -> inherited.getName().equals(group))) continue;
            } else if (!user.getCachedData().getPermissionData(options).checkPermission(permission).asBoolean()) {
                continue;
            }
            return Optional.of(this.prefixStyle(user, options));
        }
        return Optional.empty();
    }

    private PlayerNameStyle prefixStyle(User user, QueryOptions options) {
        String prefix = user.getCachedData().getMetaData(options).getPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("Missing LuckPerms prefix for user " + user.getUniqueId());
        }
        return new PlayerNameStyle(prefix);
    }
}
