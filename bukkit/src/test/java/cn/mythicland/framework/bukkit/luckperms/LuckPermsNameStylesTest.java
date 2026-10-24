package cn.mythicland.framework.bukkit.luckperms;

import cn.mythicland.framework.text.PlayerNameStyle;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LuckPermsNameStylesTest {
    private final LuckPerms api = mock(LuckPerms.class);
    private final LuckPermsNameStyles styles = new LuckPermsNameStyles(this.api);
    private final User user = mock(User.class);
    private final QueryOptions options = mock(QueryOptions.class);

    @Test
    void inheritedStaffUsesEffectivePrefixAndItsColor() {
        this.staffPrefix("&c&l[管理员] ");
        PlayerNameStyle style = this.styles.groupStyle(this.user, this.options, "staff").orElseThrow();
        assertEquals("&cSteve", style.coloredName("Steve"));
        assertEquals("&c&l[管理员] &cSteve", style.prefixedName("Steve"));
        verify(this.user, never()).getPrimaryGroup();
    }

    @Test
    void permissionGrantAloneDoesNotSelectStaffStyle() {
        when(this.user.getInheritedGroups(this.options)).thenReturn(List.of());
        assertTrue(this.styles.groupStyle(this.user, this.options, "staff").isEmpty());
        verify(this.user, never()).getCachedData();
    }

    @Test
    void missingStaffPrefixIsAConfigurationError() {
        this.staffPrefix(null);
        assertThrows(IllegalStateException.class, () -> this.styles.groupStyle(this.user, this.options, "staff"));
    }

    @Test
    void offlineLookupWaitsForUserAndOnlyLoadsMembershipForNonStaff() {
        UUID playerId = UUID.randomUUID();
        UserManager users = mock(UserManager.class);
        when(this.api.getUserManager()).thenReturn(users);
        CompletableFuture<User> loaded = new CompletableFuture<>();
        when(users.loadUser(playerId)).thenReturn(loaded);
        when(this.user.getQueryOptions()).thenReturn(this.options);
        this.staffPrefix("&4[服主]");
        CompletableFuture<PlayerNameStyle> result = this.styles.lookup(playerId, "staff", () -> {
            fail("Staff style must not load membership data");
            return null;
        });
        assertFalse(result.isDone());
        loaded.complete(this.user);
        assertEquals("&4Steve", result.join().coloredName("Steve"));

        when(this.user.getInheritedGroups(this.options)).thenReturn(List.of());
        PlayerNameStyle member = new PlayerNameStyle("&b[MVP]");
        assertSame(member, this.styles.lookup(playerId, "staff",
                () -> CompletableFuture.completedFuture(member)).join());
    }

    @Test
    void loadedUserLookupDoesNotReloadPermissions() {
        UUID playerId = UUID.randomUUID();
        UserManager users = mock(UserManager.class);
        when(this.api.getUserManager()).thenReturn(users);
        when(users.getUser(playerId)).thenReturn(this.user);
        when(this.user.getQueryOptions()).thenReturn(this.options);
        this.staffPrefix("&4[服主]");

        PlayerNameStyle result = this.styles.lookup(playerId, "staff", () -> {
            fail("Staff style must not load membership data");
            return null;
        }).join();

        assertEquals("&4Steve", result.coloredName("Steve"));
        verify(users, never()).loadUser(any(UUID.class));
        when(this.user.getInheritedGroups(this.options)).thenReturn(List.of());
        PlayerNameStyle member = new PlayerNameStyle("&b[MVP]");
        assertSame(member, this.styles.lookup(playerId, "staff",
                () -> CompletableFuture.completedFuture(member)).join());
        verify(users, never()).loadUser(any(UUID.class));
    }

    private void staffPrefix(String prefix) {
        Group staff = mock(Group.class);
        when(staff.getName()).thenReturn("staff");
        when(this.user.getInheritedGroups(this.options)).thenReturn(List.of(staff));
        CachedDataManager cached = mock(CachedDataManager.class);
        CachedMetaData meta = mock(CachedMetaData.class);
        when(this.user.getCachedData()).thenReturn(cached);
        when(cached.getMetaData(this.options)).thenReturn(meta);
        when(meta.getPrefix()).thenReturn(prefix);
    }
}
