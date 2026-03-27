package com.swag.chunkhopper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ChunkHopperGUI implements Listener {

    private final ChunkHopperPlugin plugin;
    private final ChunkHopperManager manager;

    // All filter slot indices in the 54-slot GUI
    private static final int[] FILTER_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    // O(1) lookup set for click routing
    private static final Set<Integer> FILTER_SLOT_SET = new HashSet<>(
            Arrays.asList(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43));

    public ChunkHopperGUI(ChunkHopperPlugin plugin, ChunkHopperManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ---------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------

    public void openGUI(Player player, Location loc) {
        UUID owner = manager.getOwner(loc);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Chunk Hopper Filter"));
        ItemStack border = createBorderPane();

        // Top row (0–8)
        for (int i = 0; i <= 8; i++) inv.setItem(i, border);

        // Bottom row (45–53) — buttons at 46, 48, 50, 52 (evenly spaced), rest are border
        for (int i = 45; i <= 53; i++) {
            if (i != 46 && i != 48 && i != 50 && i != 52) inv.setItem(i, border);
        }

        // Left column (9, 18, 27, 36, 45 already covered)
        for (int i = 9; i < 45; i += 9) inv.setItem(i, border);

        // Right column (17, 26, 35, 44, 53 already covered)
        for (int i = 17; i < 54; i += 9) inv.setItem(i, border);

        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) {
            player.sendMessage(Component.text("No data for this Chunk Hopper.", NamedTextColor.RED));
            return;
        }

        FilterMode currentMode = data.mode;
        List<Material> filters = data.filterItems;

        // Also fill extra "non-filter" interior slots with border (10,12,14,16,19,25,28,34,37,43)
        int[] extraBorderSlots = {11, 12, 14, 15};
        for (int s : extraBorderSlots) inv.setItem(s, border);

        // Mode buttons
        inv.setItem(10, createModeButton(FilterMode.ALL, currentMode));
        inv.setItem(13, createModeButton(FilterMode.WHITELIST, currentMode));
        inv.setItem(16, createModeButton(FilterMode.BLACKLIST, currentMode));

        // Bottom row buttons — evenly spaced at 46, 48, 50, 52
        inv.setItem(46, createUpgradesButton(data));
        inv.setItem(48, createVoidFilterButton(data));
        inv.setItem(50, createInfoButton(currentMode, owner));
        inv.setItem(52, createInventoryButton(data));

        // Populate filter slots with current filter items
        for (int i = 0; i < filters.size() && i < FILTER_SLOTS.length; i++) {
            inv.setItem(FILTER_SLOTS[i], new ItemStack(filters.get(i)));
        }

        player.setMetadata("chunkhopper_location", new FixedMetadataValue(plugin, loc));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Item builders
    // ---------------------------------------------------------------

    private ItemStack createBorderPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(""));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createModeButton(FilterMode mode, FilterMode current) {
        List<Component> lore = new ArrayList<>();
        Material mat;
        Component name;

        switch (mode) {
            case ALL -> {
                mat = Material.LIME_STAINED_GLASS_PANE;
                name = Component.text("All Items", NamedTextColor.GREEN, TextDecoration.BOLD);
                lore.add(Component.text("Collect every dropped item", NamedTextColor.GRAY));
            }
            case WHITELIST -> {
                mat = Material.BLUE_STAINED_GLASS_PANE;
                name = Component.text("Whitelist Mode", NamedTextColor.BLUE, TextDecoration.BOLD);
                lore.add(Component.text("Only collect items listed below", NamedTextColor.GRAY));
            }
            case BLACKLIST -> {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = Component.text("Blacklist Mode", NamedTextColor.RED, TextDecoration.BOLD);
                lore.add(Component.text("Collect all items EXCEPT the ones listed", NamedTextColor.GRAY));
            }
            default -> {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = Component.text("Unknown");
            }
        }

        if (mode == current) {
            lore.add(Component.text("✔ Active", NamedTextColor.YELLOW));
        } else {
            lore.add(Component.text("Click to select", NamedTextColor.WHITE));
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoButton(FilterMode mode, UUID owner) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Mode: " + mode.name(), NamedTextColor.YELLOW));
            lore.add(Component.empty());
            lore.add(Component.text("Place items into the filter", NamedTextColor.GRAY));
            lore.add(Component.text("slots to customize collection.", NamedTextColor.GRAY));
            lore.add(Component.empty());
            String ownerName = owner != null ? Bukkit.getOfflinePlayer(owner).getName() : "Unknown";
            lore.add(Component.text("Owner: " + ownerName, NamedTextColor.GOLD));
            lore.add(Component.empty());
            lore.add(Component.text("Click for stats", NamedTextColor.WHITE));

            meta.displayName(Component.text("Hopper Info", NamedTextColor.GOLD, TextDecoration.BOLD));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createUpgradesButton(ChunkHopperManager.HopperData data) {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Upgrades", NamedTextColor.YELLOW, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            for (UpgradeType type : UpgradeType.values()) {
                int tier = data.upgrades.getOrDefault(type, 0);
                lore.add(Component.text(type.name().replace("_", " ") + ": Tier " + tier, NamedTextColor.GRAY));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Click to manage upgrades", NamedTextColor.WHITE));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createVoidFilterButton(ChunkHopperManager.HopperData data) {
        int tier = data.upgrades.getOrDefault(UpgradeType.VOID_FILTER, 0);
        boolean unlocked = tier > 0;
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (unlocked) {
                meta.displayName(Component.text("Void Filter", NamedTextColor.RED, TextDecoration.BOLD));
                meta.lore(List.of(
                    Component.text("Void slots: " + tier, NamedTextColor.GRAY),
                    Component.text("Items here are destroyed on spawn.", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to configure", NamedTextColor.WHITE)
                ));
            } else {
                meta.displayName(Component.text("Void Filter", NamedTextColor.DARK_GRAY, TextDecoration.BOLD));
                meta.lore(List.of(
                    Component.text("Purchase the Void Filter upgrade to unlock.", NamedTextColor.GRAY)
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInventoryButton(ChunkHopperManager.HopperData data) {
        boolean hasCapacity = data.upgrades.getOrDefault(UpgradeType.CAPACITY, 0) > 0;
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (hasCapacity) {
                meta.displayName(Component.text("Virtual Inventory", NamedTextColor.AQUA, TextDecoration.BOLD));
                long totalStored = data.virtualStorage.stream().mapToLong(e -> e.count).sum();
                meta.lore(List.of(
                        Component.text("Total stored: " + NumberFormat.getInstance().format(totalStored) + " items", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Click to manage inventory", NamedTextColor.WHITE)
                ));
            } else {
                meta.displayName(Component.text("Virtual Inventory", NamedTextColor.DARK_GRAY, TextDecoration.BOLD));
                meta.lore(List.of(
                        Component.text("Purchase Capacity upgrade to unlock.", NamedTextColor.GRAY)
                ));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;

        // Title check
        if (!event.getView().title().equals(Component.text("Chunk Hopper Filter"))) return;

        Location loc = getTrackedHopper(player);
        if (loc == null) return;

        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;

        int slot = event.getRawSlot();

        // Player inventory — allow normal clicks so they can pick items up onto cursor.
        // Only intercept shift-clicks to add directly to filter.
        if (slot >= 54) {
            if (!event.isShiftClick()) return;
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            if (data.filterItems.contains(clicked.getType())) return;

            int maxFilters = plugin.getConfig().getInt("max-filter-items", FILTER_SLOTS.length);
            if (data.filterItems.size() >= maxFilters) return;

            data.filterItems.add(clicked.getType());
            refreshFilterArea(event.getInventory(), data.filterItems);
            ChunkHopperEffects.filterAdded(player);
            return;
        }

        // All GUI slot clicks are cancelled from here
        event.setCancelled(true);

        // Upgrades button
        if (slot == 46) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getUpgradeGUI().openGUI(player, loc), 1L);
            return;
        }

        // Void filter button
        if (slot == 48) {
            if (data.upgrades.getOrDefault(UpgradeType.VOID_FILTER, 0) > 0) {
                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getVoidFilterGUI().openGUI(player, loc), 1L);
            } else {
                player.sendMessage(Component.text("Purchase the Void Filter upgrade to unlock this feature!", NamedTextColor.RED));
            }
            return;
        }

        // Inventory button
        if (slot == 52) {
            ChunkHopperManager.HopperData checkData = manager.getData(loc);
            if (checkData != null && checkData.upgrades.getOrDefault(UpgradeType.CAPACITY, 0) > 0) {
                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getInventoryGUI().openGUI(player, loc), 1L);
            } else {
                player.sendMessage(Component.text("Purchase the Capacity upgrade to access virtual storage!", NamedTextColor.RED));
            }
            return;
        }

        // Border slots are no-op (except slot 50 which is the info button)
        if (isBorderSlot(slot) && slot != 50) return;

        // Mode buttons
        if (slot == 10 || slot == 13 || slot == 16) {
            FilterMode newMode = switch (slot) {
                case 10 -> FilterMode.ALL;
                case 13 -> FilterMode.WHITELIST;
                case 16 -> FilterMode.BLACKLIST;
                default -> FilterMode.ALL;
            };
            data.mode = newMode;
            ChunkHopperEffects.modeChanged(player);
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> openGUI(player, loc), 1L);
            return;
        }

        // Info / stats button
        if (slot == 50) {
            openStatsGUI(player, loc);
            return;
        }

        // Filter slots
        if (!FILTER_SLOT_SET.contains(slot)) return;

        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Right-click or left-click on existing filter item → remove
        if (currentItem != null && currentItem.getType() != Material.AIR) {
            data.filterItems.remove(currentItem.getType());
            refreshFilterArea(event.getInventory(), data.filterItems);
            ChunkHopperEffects.filterRemoved(player);
            return;
        }

        // Placing cursor item → add to filter
        if (cursor != null && cursor.getType() != Material.AIR) {
            if (data.filterItems.contains(cursor.getType())) return;

            int maxFilters = plugin.getConfig().getInt("max-filter-items", FILTER_SLOTS.length);
            if (data.filterItems.size() >= maxFilters) return;

            data.filterItems.add(cursor.getType());
            refreshFilterArea(event.getInventory(), data.filterItems);
            ChunkHopperEffects.filterAdded(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getView().title().equals(Component.text("Chunk Hopper Filter"))) return;
        // Cancel any drag that touches the GUI inventory (slots 0–53)
        for (int slot : event.getRawSlots()) {
            if (slot < 54) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;
        if (!event.getView().title().equals(Component.text("Chunk Hopper Filter"))) return;
        player.removeMetadata("chunkhopper_location", plugin);
    }

    // ---------------------------------------------------------------
    // Stats GUI (chat messages)
    // ---------------------------------------------------------------

    public void openStatsGUI(Player player, Location loc) {
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) {
            player.sendMessage(Component.text("No data for this Chunk Hopper.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Total collected: " + data.totalCollected, NamedTextColor.GOLD));
        player.sendMessage(Component.text("Session collected: " + data.sessionCollected, NamedTextColor.YELLOW));
        ChunkHopperEffects.statsViewed(player);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private boolean isBorderSlot(int slot) {
        // Top row, bottom row, left/right columns
        if (slot <= 8) return true;
        if (slot >= 45) return true;
        if (slot % 9 == 0) return true;
        if (slot % 9 == 8) return true;
        return false;
    }

    private void refreshFilterArea(Inventory inv, List<Material> filters) {
        for (int slot : FILTER_SLOTS) {
            inv.setItem(slot, null);
        }
        for (int i = 0; i < filters.size() && i < FILTER_SLOTS.length; i++) {
            inv.setItem(FILTER_SLOTS[i], new ItemStack(filters.get(i)));
        }
    }

    private Location getTrackedHopper(Player player) {
        if (!player.hasMetadata("chunkhopper_location")) return null;
        List<MetadataValue> meta = player.getMetadata("chunkhopper_location");
        if (meta.isEmpty()) return null;
        Object val = meta.get(0).value();
        return val instanceof Location ? (Location) val : null;
    }
}
