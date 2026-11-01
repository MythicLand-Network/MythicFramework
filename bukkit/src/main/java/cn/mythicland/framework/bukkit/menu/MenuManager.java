package cn.mythicland.framework.bukkit.menu;

import cn.mythicland.framework.bukkit.component.ListenerComponent;
import cn.mythicland.framework.bukkit.task.ScheduledTask;
import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentArguments;
import cn.mythicland.framework.component.ComponentResolver;
import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

@InjectComponent
@ListenerComponent
public final class MenuManager implements Listener, ManagedLifecycle, AutoCloseable {
    private final JavaPlugin plugin;
    private final ComponentResolver componentResolver;
    private final TaskScheduler taskScheduler;
    private final Map<UUID, OpenMenuSession> openMenus;
    private long tickCounter;
    private Predicate<Player> interactionAllowed = player -> true;

    public MenuManager(
            JavaPlugin plugin,
            ComponentResolver componentResolver,
            TaskScheduler taskScheduler
    ) {
        this.plugin = plugin;
        this.componentResolver = componentResolver;
        this.taskScheduler = taskScheduler;
        this.openMenus = new ConcurrentHashMap<>();
    }

    public void interactionAllowed(Predicate<Player> interactionAllowed) {
        this.interactionAllowed = interactionAllowed;
    }

    public void open(Player player, AbstractMenu menu) {
        if (player == null || menu == null) return;
        this.taskScheduler.executeSyncIfNeeded(() -> this.openNow(player, menu));
    }

    public <T extends AbstractMenu> void open(
            Player player,
            Class<T> menuType,
            ComponentArguments<?> arguments
    ) {
        this.open(player, this.componentResolver.create(menuType, arguments));
    }

    public AbstractMenu currentMenu(Player player) {
        if (player == null) return null;
        OpenMenuSession session = this.openMenus.get(player.getUniqueId());
        return session == null ? null : session.menu();
    }

    public void refresh(Player player) {
        if (player == null) return;
        this.taskScheduler.executeSyncIfNeeded(() -> {
            OpenMenuSession session = this.openMenus.get(player.getUniqueId());
            if (session != null) this.refreshInPlace(player, session, false);
        });
    }

    public void close(Player player) {
        if (player == null) return;
        this.taskScheduler.executeSyncIfNeeded(() -> this.closeNow(player));
    }

    public void closeNextTick(Player player) {
        if (player == null) return;
        this.taskScheduler.executeSyncLater(() -> this.closeNow(player), 1L);
    }

    public boolean hasOpenMenu(UUID playerUniqueId) {
        return playerUniqueId != null && this.openMenus.containsKey(playerUniqueId);
    }

    public boolean isManagedInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MenuHolder;
    }

    public void clearSessions() {
        this.taskScheduler.executeSyncIfNeeded(this::clearSessionsNow);
    }

    @Override
    public void close() {
        this.unload();
    }

    @Override
    public void unload() {
        this.clearSessionsNow();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (this.isLateMenu(event.getView().getTopInventory())) return;
        this.handleClick(event);
    }

    public void handleClick(InventoryClickEvent event) {
        if (this.isInventoryMenu(event.getView().getTopInventory())) return;
        MenuInteraction interaction = this.resolveMenuInteraction(event);
        if (interaction == null) return;
        Player player = interaction.player();
        MenuHolder holder = interaction.holder();
        event.setCancelled(true);
        if (!this.interactionAllowed.test(player)) return;
        ClickType clickType = ClickType.fromClickType(event.getClick());
        if (clickType == null) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) return;
        if (rawSlot >= interaction.topInventory().getSize()) {
            if (holder.menu() instanceof PlayerInventoryMenuHandler inventoryHandler
                    && inventoryHandler.handlePlayerInventoryClick(player, event, this)) {
                this.refreshIfCurrent(player, holder);
            }
            return;
        }
        AbstractButton button = holder.contents().getButton(rawSlot);
        if (button == null) {
            if (holder.menu().shouldRefreshOnEmptySlotClick(player, rawSlot, clickType)) {
                this.refreshIfCurrent(player, holder);
            }
            return;
        }
        button.onClick(player, clickType, this);
        OpenMenuSession session = this.openMenus.get(player.getUniqueId());
        if (session == null || session.holder() != holder) return;
        if (button.shouldClose(player, clickType)) {
            this.closeNextTick(player);
        } else if (button.shouldRefresh(player, clickType)) {
            this.refreshInPlace(player, session, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.isCancelled()) return;
        MenuHolder holder = this.findMenuHolder(event.getView().getTopInventory(), player.getUniqueId());
        if (holder == null) return;
        if (holder.menu().handlesClicksAtHighestPriority()) {
            this.handleClick(event);
            return;
        }
        if (!(holder.menu() instanceof InventoryMenu menu)) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= 0) menu.handleClick(player, event, this);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (this.isLateMenu(event.getView().getTopInventory())) return;
        this.handleDrag(event);
    }

    public void handleDrag(InventoryDragEvent event) {
        if (this.isInventoryMenu(event.getView().getTopInventory())) return;
        MenuInteraction interaction = this.resolveMenuInteraction(event);
        if (interaction == null) return;
        if (!this.interactionAllowed.test(interaction.player())) {
            event.setCancelled(true);
            return;
        }
        if (interaction.holder().menu() instanceof PlayerInventoryMenuHandler inventoryHandler
                && inventoryHandler.handlePlayerInventoryDrag(interaction.player(), event, this)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMenuDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.isCancelled()) return;
        Inventory inventory = event.getView().getTopInventory();
        if (inventory == null || !(inventory.getHolder() instanceof MenuHolder holder)) return;
        if (holder.menu().handlesClicksAtHighestPriority()) {
            this.handleDrag(event);
            return;
        }
        if (!(holder.menu() instanceof InventoryMenu menu)) return;
        event.setCancelled(true);
        menu.handleDrag(player, event, this);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        this.handleClose(event);
    }

    public void handleClose(InventoryCloseEvent event) {
        if (this.isInventoryMenu(event.getInventory()) || this.isLateMenu(event.getInventory())) return;
        this.closeInventoryEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryMenuClose(InventoryCloseEvent event) {
        if (this.isInventoryMenu(event.getInventory()) || this.isLateMenu(event.getInventory())) {
            this.closeInventoryEvent(event);
        }
    }

    private void closeInventoryEvent(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        MenuHolder holder = this.findMenuHolder(event.getInventory(), player.getUniqueId());
        if (holder == null) return;
        OpenMenuSession session = this.openMenus.get(player.getUniqueId());
        if ((holder.menu() instanceof InventoryMenu || holder.menu().handlesClicksAtHighestPriority())
                && (session == null || session.holder() != holder)) return;
        if (session != null && session.holder() == holder) {
            this.openMenus.remove(player.getUniqueId(), session);
            this.clearPageSession(player, session.menu());
        }
        holder.close(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        OpenMenuSession session = this.openMenus.get(event.getPlayer().getUniqueId());
        if (!this.isLateSession(session)) this.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryMenuQuit(PlayerQuitEvent event) {
        OpenMenuSession session = this.openMenus.get(event.getPlayer().getUniqueId());
        if (this.isLateSession(session)) this.handleQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        OpenMenuSession session = this.openMenus.get(event.getPlayer().getUniqueId());
        if (!this.isLateSession(session)) this.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(this.plugin)) this.clearSessions();
    }

    @ScheduledTask(delayTicks = 1L, periodTicks = 1L)
    private void tickAutoRefresh() {
        this.tickCounter++;
        for (Map.Entry<UUID, OpenMenuSession> entry : this.openMenus.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            OpenMenuSession session = entry.getValue();
            if (!(session.menu() instanceof AutoRefreshMenu autoRefreshMenu)) continue;
            if (!autoRefreshMenu.shouldAutoRefresh(player)) continue;
            long interval = Math.max(1L, autoRefreshMenu.autoRefreshIntervalTicks(player));
            if (this.tickCounter - session.lastRefreshTick() < interval) continue;
            if (this.findMenuHolder(player.getOpenInventory().getTopInventory(), player.getUniqueId())
                    != session.holder()) continue;
            session.lastRefreshTick(this.tickCounter);
            this.refreshInPlace(player, session, true);
        }
    }

    public void handleQuit(Player player) {
        if (player == null) return;
        UUID playerUniqueId = player.getUniqueId();
        OpenMenuSession session = this.openMenus.remove(playerUniqueId);
        if (session != null) this.clearPageSession(player, session.menu());
        PaginatedMenuSessionRegistry.clear(playerUniqueId);
        MenuHolder holder = session == null
                ? this.findMenuHolder(player.getOpenInventory().getTopInventory(), playerUniqueId)
                : session.holder();
        if (holder != null) {
            holder.quit(player);
        }
    }

    private void openNow(Player player, AbstractMenu menu) {
        if (!(menu instanceof InventoryMenu) && menu.replacesInPlace()) {
            OpenMenuSession current = this.openMenus.get(player.getUniqueId());
            if (current != null && current.holder().getInventory() != null
                    && this.findMenuHolder(player.getOpenInventory().getTopInventory(), player.getUniqueId())
                    == current.holder()
                    && menu.resolveTitle(player).equals(current.holder().getInventory().getTitle())) {
                Menu contents = menu.buildMenu(player);
                if (contents.getSize() == current.holder().getInventory().getSize()) {
                    Inventory target = current.holder().getInventory();
                    Inventory staging = Bukkit.createInventory(null, contents.getSize(), menu.resolveTitle(player));
                    for (Map.Entry<Integer, AbstractButton> entry : contents.getButtons().entrySet()) {
                        staging.setItem(entry.getKey(), entry.getValue().buildDisplayItem(player));
                    }
                    AbstractMenu previous = current.menu();
                    current.menu = menu;
                    current.holder().menu = menu;
                    current.holder().replaceContents(contents);
                    target.setContents(staging.getContents());
                    player.updateInventory();
                    previous.onClose(player, target);
                    return;
                }
            }
        }
        if (menu instanceof InventoryMenu inventoryMenu) {
            OpenMenuSession current = this.openMenus.get(player.getUniqueId());
            if (current != null && current.menu() instanceof InventoryMenu
                    && current.holder().getInventory() != null
                    && this.findMenuHolder(player.getOpenInventory().getTopInventory(), player.getUniqueId())
                    == current.holder()
                    && inventoryMenu.size(player) == current.holder().getInventory().getSize()
                    && inventoryMenu.resolveTitle(player).equals(current.holder().getInventory().getTitle())) {
                this.replaceInventoryMenu(player, current, inventoryMenu);
                return;
            }
            this.closeNow(player);
        }
        MenuHolder holder = null;
        try {
            Menu contents = menu instanceof InventoryMenu inventoryMenu
                    ? new Menu(inventoryMenu.resolveRowCount(player) * 9)
                    : menu.buildMenu(player);
            holder = new MenuHolder(player.getUniqueId(), menu, contents);
            Inventory inventory = Bukkit.createInventory(holder, contents.getSize(), menu.resolveTitle(player));
            holder.bind(inventory);
            if (menu instanceof InventoryMenu inventoryMenu) {
                inventoryMenu.render(player, inventory);
            } else {
                for (Map.Entry<Integer, AbstractButton> entry : contents.getButtons().entrySet()) {
                    inventory.setItem(entry.getKey(), entry.getValue().buildDisplayItem(player));
                }
            }
        } catch (RuntimeException | Error exception) {
            this.closeAfterFailure(holder, player, exception);
            throw exception;
        }

        OpenMenuSession previousSession = this.openMenus.get(player.getUniqueId());
        if (previousSession != null) {
            try {
                previousSession.holder().close(player);
            } catch (RuntimeException | Error exception) {
                this.openMenus.remove(player.getUniqueId(), previousSession);
                this.clearPageSession(player, previousSession.menu());
                this.closeAfterFailure(holder, player, exception);
                throw exception;
            }
            this.openMenus.remove(player.getUniqueId(), previousSession);
            if (previousSession.menu() != menu) this.clearPageSession(player, previousSession.menu());
        }

        if (menu instanceof AbstractPaginatedMenu<?> paginatedMenu) paginatedMenu.markOpened(player);
        OpenMenuSession session = new OpenMenuSession(menu, holder, this.tickCounter);
        this.openMenus.put(player.getUniqueId(), session);
        try {
            player.openInventory(holder.getInventory());
        } catch (RuntimeException | Error exception) {
            this.openMenus.remove(player.getUniqueId(), session);
            this.clearPageSession(player, menu);
            this.closeAfterFailure(holder, player, exception);
            throw exception;
        }
    }

    private void closeNow(Player player) {
        OpenMenuSession session = this.openMenus.remove(player.getUniqueId());
        try {
            if (session != null) {
                this.clearPageSession(player, session.menu());
                session.holder().close(player);
            }
        } finally {
            player.closeInventory();
        }
    }

    private void clearSessionsNow() {
        Throwable failure = null;
        for (HumanEntity viewer : Bukkit.getOnlinePlayers()) {
            if (!(viewer instanceof Player player)) continue;
            MenuHolder holder = this.findMenuHolder(
                    viewer.getOpenInventory().getTopInventory(), player.getUniqueId()
            );
            if (holder == null) continue;
            try {
                this.openMenus.remove(player.getUniqueId());
                this.clearPageSession(player, holder.menu());
                holder.close(player);
            } catch (RuntimeException | Error exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            } finally {
                viewer.closeInventory();
            }
        }
        this.openMenus.clear();
        PaginatedMenuSessionRegistry.clearAll();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    private MenuInteraction resolveMenuInteraction(InventoryInteractEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return null;
        Inventory topInventory = event.getView().getTopInventory();
        MenuHolder holder = this.findMenuHolder(topInventory, player.getUniqueId());
        if (holder == null || holder.menu().allowsInventoryInteraction()) return null;
        return new MenuInteraction(player, topInventory, holder);
    }

    private void refreshIfCurrent(Player player, MenuHolder expectedHolder) {
        OpenMenuSession session = this.openMenus.get(player.getUniqueId());
        if (session != null && session.holder() == expectedHolder) {
            this.refreshInPlace(player, session, false);
        }
    }

    private void refreshInPlace(
            Player player,
            OpenMenuSession session,
            boolean allowIncrementalRefresh
    ) {
        AbstractMenu menu = session.menu();
        MenuHolder holder = session.holder();
        Inventory inventory = holder.getInventory();
        if (inventory == null) return;
        if (menu instanceof InventoryMenu inventoryMenu) {
            if (inventoryMenu.size(player) != inventory.getSize()) this.openNow(player, menu);
            else {
                this.renderInventoryMenu(player, inventoryMenu, inventory);
                player.updateInventory();
            }
            return;
        }
        if (allowIncrementalRefresh && menu instanceof AutoRefreshMenu autoRefreshMenu
                && autoRefreshMenu.refreshInPlace(player, inventory, holder.contents())) {
            if (autoRefreshMenu.shouldForceInventorySync(player)) player.updateInventory();
            return;
        }
        Menu rebuilt = menu.buildMenu(player);
        if (inventory.getSize() != rebuilt.getSize()
                || !player.getOpenInventory().getTitle().equals(menu.resolveTitle(player))) {
            this.open(player, menu);
            return;
        }
        holder.replaceContents(rebuilt);
        for (int slot = 0; slot < rebuilt.getSize(); slot++) {
            AbstractButton button = rebuilt.getButtons().get(slot);
            ItemStack item = button == null ? null : button.buildDisplayItem(player);
            if (!Objects.equals(inventory.getItem(slot), item)) inventory.setItem(slot, item);
        }
        if (menu instanceof AutoRefreshMenu autoRefreshMenu
                && autoRefreshMenu.shouldForceInventorySync(player)) player.updateInventory();
    }

    private void replaceInventoryMenu(Player player, OpenMenuSession session, InventoryMenu menu) {
        MenuHolder holder = session.holder();
        InventoryMenu previous = (InventoryMenu) session.menu();
        holder.menu = menu;
        session.menu = menu;
        try {
            this.renderInventoryMenu(player, menu, holder.getInventory());
            player.updateInventory();
            previous.onClose(player, holder.getInventory());
        } catch (RuntimeException exception) {
            holder.menu = previous;
            session.menu = previous;
            this.openMenus.remove(player.getUniqueId(), session);
            player.closeInventory();
            throw exception;
        }
    }

    private void renderInventoryMenu(Player player, InventoryMenu menu, Inventory target) {
        Inventory staging = Bukkit.createInventory(null, target.getSize(), menu.resolveTitle(player));
        menu.render(player, staging);
        target.setContents(Arrays.copyOf(staging.getContents(), staging.getSize()));
    }

    private boolean isLateSession(OpenMenuSession session) {
        return session != null && (session.menu() instanceof InventoryMenu
                || session.menu().handlesClicksAtHighestPriority());
    }

    private boolean isLateMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MenuHolder holder
                && holder.menu().handlesClicksAtHighestPriority();
    }

    private boolean isInventoryMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MenuHolder holder
                && holder.menu() instanceof InventoryMenu;
    }

    private void clearPageSession(Player player, AbstractMenu menu) {
        if (menu instanceof AbstractPaginatedMenu<?> paginatedMenu) paginatedMenu.clearPageSession(player);
    }

    private void closeAfterFailure(MenuHolder holder, Player player, Throwable failure) {
        if (holder == null) return;
        try {
            holder.close(player);
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private MenuHolder findMenuHolder(Inventory inventory, UUID viewerUniqueId) {
        if (inventory == null || viewerUniqueId == null) return null;
        if (!(inventory.getHolder() instanceof MenuHolder holder)) return null;
        return viewerUniqueId.equals(holder.viewerUniqueId()) ? holder : null;
    }

    private static final class OpenMenuSession {
        private AbstractMenu menu;
        private final MenuHolder holder;
        private long lastRefreshTick;

        private OpenMenuSession(AbstractMenu menu, MenuHolder holder, long lastRefreshTick) {
            this.menu = menu;
            this.holder = holder;
            this.lastRefreshTick = lastRefreshTick;
        }

        private AbstractMenu menu() {
            return this.menu;
        }

        private MenuHolder holder() {
            return this.holder;
        }

        private long lastRefreshTick() {
            return this.lastRefreshTick;
        }

        private void lastRefreshTick(long tick) {
            this.lastRefreshTick = tick;
        }
    }

    private record MenuInteraction(Player player, Inventory topInventory, MenuHolder holder) {
    }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID viewerUniqueId;
        private AbstractMenu menu;
        private Menu contents;
        private Inventory inventory;

        private MenuHolder(UUID viewerUniqueId, AbstractMenu menu, Menu contents) {
            this.viewerUniqueId = viewerUniqueId;
            this.menu = menu;
            this.contents = contents;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID viewerUniqueId() {
            return this.viewerUniqueId;
        }

        private AbstractMenu menu() {
            return this.menu;
        }

        private Menu contents() {
            return this.contents;
        }

        private void replaceContents(Menu contents) {
            this.contents.clearButtons();
            this.contents = contents;
        }

        private void quit(Player player) {
            Inventory closingInventory = this.inventory;
            this.inventory = null;
            try {
                if (closingInventory != null) this.menu.onQuit(player, closingInventory);
            } finally {
                this.contents.clearButtons();
            }
        }

        private void close(Player player) {
            Inventory closingInventory = this.inventory;
            this.inventory = null;
            try {
                if (closingInventory != null) this.menu.onClose(player, closingInventory);
            } finally {
                this.contents.clearButtons();
            }
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }
}
