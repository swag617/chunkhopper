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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VoidFilterGUI implements Listener {

    private static final Component TITLE = Component.text("Chunk Hopper Void Filter");
    private static final int[] FILTER_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final Set<Integer> FILTER_SLOT_SET = new HashSet<>(
            Arrays.asList(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43));

    private final ChunkHopperPlugin plugin;
    private final ChunkHopperManager manager;

    public VoidFilterGUI(ChunkHopperPlugin plugin, ChunkHopperManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openGUI(Player player, Location loc) {
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;

        int unlockedSlots = data.upgrades.getOrDefault(UpgradeType.VOID_FILTER, 0);

        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        ItemStack border = createBorder();
        ItemStack lockedSlot = createLockedSlot();

        // Top and bottom rows
        for (int i = 0; i <= 8; i++) inv.setItem(i, border);
        for (int i = 45; i <= 53; i++) { if (i != 49) inv.setItem(i, border); }

        // Left and right columns
        for (int i = 9; i < 45; i += 9) inv.setItem(i, border);
        for (int i = 17; i < 54; i += 9) inv.setItem(i, border);

        // Row 2 border slots (surround the centered info label)
        int[] extraBorder = {10, 11, 12, 14, 15, 16};
        for (int s : extraBorder) inv.setItem(s, border);

        // Info label centered at slot 13
        inv.setItem(13, createInfoLabel());

        // Populate filter slots
        List<Material> voidList = new ArrayList<>(data.voidFilter);
        for (int i = 0; i < FILTER_SLOTS.length; i++) {
            int slotIndex = FILTER_SLOTS[i];
            if (i < unlockedSlots) {
                if (i < voidList.size()) {
                    inv.setItem(slotIndex, new ItemStack(voidList.get(i)));
                }
                // else leave empty (available for placement)
            } else {
                inv.setItem(slotIndex, lockedSlot);
            }
        }

        inv.setItem(49, createBackButton());

        player.setMetadata("chunkhopper_void_loc", new FixedMetadataValue(plugin, loc));
        player.openInventory(inv);
    }

    private ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(""));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createLockedSlot() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Locked", NamedTextColor.RED, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Purchase Void Filter upgrade to unlock", NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoLabel() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Void Filter", NamedTextColor.RED, TextDecoration.BOLD));
            meta.lore(List.of(
                    Component.text("Items in these slots are destroyed", NamedTextColor.GRAY),
                    Component.text("when they spawn in this chunk.", NamedTextColor.GRAY)
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBackButton() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("« Back", NamedTextColor.GRAY, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Return to Upgrades", NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;
        if (!event.getView().title().equals(TITLE)) return;

        Location loc = getTrackedLoc(player);
        if (loc == null) return;
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;

        int unlockedSlots = data.upgrades.getOrDefault(UpgradeType.VOID_FILTER, 0);
        int slot = event.getRawSlot();

        // Player inventory — allow normal clicks so they can pick items onto cursor.
        // Only intercept shift-clicks to add directly to void filter.
        if (slot >= 54) {
            if (!event.isShiftClick()) return;
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            if (data.voidFilter.contains(clicked.getType())) return;
            if (data.voidFilter.size() >= unlockedSlots) return;
            data.voidFilter.add(clicked.getType());
            refreshGUI(player, data, unlockedSlots);
            ChunkHopperEffects.filterAdded(player);
            return;
        }

        // All GUI slot clicks cancelled from here
        event.setCancelled(true);

        // Back button
        if (slot == 49) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getUpgradeGUI().openGUI(player, loc), 1L);
            return;
        }

        if (!FILTER_SLOT_SET.contains(slot)) return;

        // Find the index in FILTER_SLOTS
        int filterIndex = -1;
        for (int i = 0; i < FILTER_SLOTS.length; i++) {
            if (FILTER_SLOTS[i] == slot) { filterIndex = i; break; }
        }
        if (filterIndex < 0 || filterIndex >= unlockedSlots) return;

        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Click on existing void filter item → remove it
        if (currentItem != null && currentItem.getType() != Material.AIR) {
            data.voidFilter.remove(currentItem.getType());
            refreshGUI(player, data, unlockedSlots);
            ChunkHopperEffects.filterRemoved(player);
            return;
        }

        // Placing cursor item → add to void filter
        if (cursor != null && cursor.getType() != Material.AIR) {
            if (data.voidFilter.contains(cursor.getType())) return;
            if (data.voidFilter.size() >= unlockedSlots) return;
            data.voidFilter.add(cursor.getType());
            refreshGUI(player, data, unlockedSlots);
            ChunkHopperEffects.filterAdded(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getView().title().equals(TITLE)) return;
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
        if (!event.getView().title().equals(TITLE)) return;
        player.removeMetadata("chunkhopper_void_loc", plugin);
    }

    private void refreshGUI(Player player, ChunkHopperManager.HopperData data, int unlockedSlots) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack lockedSlot = createLockedSlot();
        List<Material> voidList = new ArrayList<>(data.voidFilter);
        for (int i = 0; i < FILTER_SLOTS.length; i++) {
            int slotIndex = FILTER_SLOTS[i];
            if (i < unlockedSlots) {
                inv.setItem(slotIndex, i < voidList.size() ? new ItemStack(voidList.get(i)) : null);
            } else {
                inv.setItem(slotIndex, lockedSlot);
            }
        }
    }

    private Location getTrackedLoc(Player player) {
        if (!player.hasMetadata("chunkhopper_void_loc")) return null;
        List<MetadataValue> meta = player.getMetadata("chunkhopper_void_loc");
        if (meta.isEmpty()) return null;
        Object val = meta.get(0).value();
        return val instanceof Location ? (Location) val : null;
    }
}
