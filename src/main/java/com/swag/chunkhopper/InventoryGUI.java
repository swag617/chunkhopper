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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class InventoryGUI implements Listener {

    private static final Component TITLE = Component.text("Chunk Hopper Inventory");

    // Interior display slots (rows 2-5, inner columns — excluding border slots)
    private static final int[] DISPLAY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final ChunkHopperPlugin plugin;
    private final ChunkHopperManager manager;

    public InventoryGUI(ChunkHopperPlugin plugin, ChunkHopperManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openGUI(Player player, Location loc) {
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        ItemStack border = createBorder();

        // Top and bottom rows
        for (int i = 0; i <= 8; i++) inv.setItem(i, border);
        for (int i = 45; i <= 53; i++) { if (i != 49) inv.setItem(i, border); }

        // Left and right columns
        for (int i = 9; i < 45; i += 9) inv.setItem(i, border);
        for (int i = 17; i < 54; i += 9) inv.setItem(i, border);

        populateItems(inv, data);
        inv.setItem(49, createBackButton());

        player.setMetadata("chunkhopper_inv_loc", new FixedMetadataValue(plugin, loc));
        player.openInventory(inv);
    }

    private void populateItems(Inventory inv, ChunkHopperManager.HopperData data) {
        for (int s : DISPLAY_SLOTS) inv.setItem(s, null);

        List<ChunkHopperManager.VirtualEntry> sorted = new ArrayList<>(data.virtualStorage);
        sorted.sort(Comparator.<ChunkHopperManager.VirtualEntry>comparingLong(e -> e.count).reversed());

        for (int i = 0; i < sorted.size() && i < DISPLAY_SLOTS.length; i++) {
            inv.setItem(DISPLAY_SLOTS[i], createStorageItem(sorted.get(i)));
        }
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

    private ItemStack createBackButton() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("« Back", NamedTextColor.GRAY, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Return to Filter GUI", NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createStorageItem(ChunkHopperManager.VirtualEntry entry) {
        ItemStack item = entry.template.clone();
        item.setAmount((int) Math.min(entry.count, 64));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String formatted = NumberFormat.getInstance().format(entry.count);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Stored: " + formatted, NamedTextColor.AQUA));
            lore.add(Component.empty());
            lore.add(Component.text("Left-click: withdraw 1 stack", NamedTextColor.WHITE));
            lore.add(Component.text("Right-click: withdraw all", NamedTextColor.WHITE));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;
        if (!event.getView().title().equals(TITLE)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        Location loc = getTrackedLoc(player);
        if (loc == null) return;
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;

        // Back button → return to main filter GUI
        if (slot == 49) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getHopperGUI().openGUI(player, loc), 1L);
            return;
        }

        // Check if clicked slot is a display slot
        boolean isDisplaySlot = false;
        for (int s : DISPLAY_SLOTS) {
            if (s == slot) { isDisplaySlot = true; break; }
        }
        if (!isDisplaySlot) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Find which display slot index was clicked
        int displayIndex = -1;
        for (int i = 0; i < DISPLAY_SLOTS.length; i++) {
            if (DISPLAY_SLOTS[i] == slot) { displayIndex = i; break; }
        }
        if (displayIndex < 0) return;

        // Rebuild sorted list to match populateItems order
        List<ChunkHopperManager.VirtualEntry> sorted = new ArrayList<>(data.virtualStorage);
        sorted.sort(Comparator.<ChunkHopperManager.VirtualEntry>comparingLong(e -> e.count).reversed());
        if (displayIndex >= sorted.size()) return;

        ChunkHopperManager.VirtualEntry matchedEntry = sorted.get(displayIndex);
        long stored = matchedEntry.count;
        if (stored <= 0) return;

        boolean rightClick = event.isRightClick();
        long toWithdraw = rightClick ? stored : Math.min(stored, matchedEntry.template.getMaxStackSize());
        long withdrawn = manager.withdrawFromVirtualStorage(loc, matchedEntry.template, toWithdraw);

        if (withdrawn > 0) {
            long remaining = withdrawn;
            while (remaining > 0) {
                int stackSize = (int) Math.min(remaining, matchedEntry.template.getMaxStackSize());
                ItemStack give = matchedEntry.template.clone();
                give.setAmount(stackSize);
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(give);
                if (!leftover.isEmpty()) {
                    for (ItemStack drop : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                }
                remaining -= stackSize;
            }
            populateItems(player.getOpenInventory().getTopInventory(), data);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;
        if (!event.getView().title().equals(TITLE)) return;
        player.removeMetadata("chunkhopper_inv_loc", plugin);
    }

    private Location getTrackedLoc(Player player) {
        if (!player.hasMetadata("chunkhopper_inv_loc")) return null;
        List<MetadataValue> meta = player.getMetadata("chunkhopper_inv_loc");
        if (meta.isEmpty()) return null;
        Object val = meta.get(0).value();
        return val instanceof Location ? (Location) val : null;
    }
}
