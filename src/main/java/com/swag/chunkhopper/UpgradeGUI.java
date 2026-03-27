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

import java.util.ArrayList;
import java.util.List;

public class UpgradeGUI implements Listener {

    private static final Component TITLE = Component.text("Chunk Hopper Upgrades");

    private final ChunkHopperPlugin plugin;
    private final ChunkHopperManager manager;

    public UpgradeGUI(ChunkHopperPlugin plugin, ChunkHopperManager manager) {
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

        // Fill non-upgrade interior slots with border
        int[] extraBorder = {10, 11, 12, 13, 14, 15, 16, 19, 21, 22, 23, 25, 28, 30, 31, 32, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int s : extraBorder) inv.setItem(s, border);

        // Upgrade buttons
        inv.setItem(20, createUpgradeButton(UpgradeType.VOID_FILTER, data));
        inv.setItem(24, createUpgradeButton(UpgradeType.CAPACITY, data));
        inv.setItem(29, createUpgradeButton(UpgradeType.COLLECTION_AMOUNT, data));
        inv.setItem(33, createUpgradeButton(UpgradeType.RADIUS, data));

        // Back button
        inv.setItem(49, createBackButton());

        player.setMetadata("chunkhopper_upgrade_loc", new FixedMetadataValue(plugin, loc));
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

    private ItemStack createUpgradeButton(UpgradeType type, ChunkHopperManager.HopperData data) {
        int currentTier = data.upgrades.getOrDefault(type, 0);
        List<Double> costs = plugin.getConfig().getDoubleList(configKey(type) + ".cost-per-tier");
        int maxTier = costs.size();
        boolean isMaxed = currentTier >= maxTier;

        Material mat;
        String displayName;
        switch (type) {
            case VOID_FILTER -> { mat = Material.BARRIER; displayName = "Void Filter"; }
            case CAPACITY -> { mat = Material.CHEST; displayName = "Capacity Upgrade"; }
            case COLLECTION_AMOUNT -> { mat = Material.HOPPER; displayName = "Collection Amount"; }
            case RADIUS -> { mat = Material.COMPASS; displayName = "Radius Upgrade"; }
            default -> { mat = Material.PAPER; displayName = "Unknown"; }
        }

        NamedTextColor nameColor = isMaxed ? NamedTextColor.GOLD
                : (currentTier > 0 ? NamedTextColor.GREEN : NamedTextColor.AQUA);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(displayName, nameColor, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Current Tier: " + currentTier + " / " + maxTier, NamedTextColor.GRAY));
            lore.add(Component.empty());
            addUpgradeDescription(lore, type, currentTier);
            lore.add(Component.empty());
            if (isMaxed) {
                lore.add(Component.text("★ MAX TIER", NamedTextColor.GOLD));
            } else if (plugin.getVaultHook() == null) {
                lore.add(Component.text("⚠ Vault not installed", NamedTextColor.RED));
            } else {
                double cost = costs.get(currentTier);
                lore.add(Component.text("Cost: " + plugin.getVaultHook().format(cost), NamedTextColor.YELLOW));
                lore.add(Component.text("Click to purchase", NamedTextColor.WHITE));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addUpgradeDescription(List<Component> lore, UpgradeType type, int currentTier) {
        switch (type) {
            case VOID_FILTER -> {
                lore.add(Component.text("Destroys specified items on spawn.", NamedTextColor.GRAY));
                lore.add(Component.text("Each tier unlocks +1 void filter slot.", NamedTextColor.GRAY));
                lore.add(Component.text("Current void slots: " + currentTier, NamedTextColor.AQUA));
            }
            case CAPACITY -> {
                lore.add(Component.text("Unlocks virtual storage.", NamedTextColor.GRAY));
                lore.add(Component.text("Items stored in memory, not the block.", NamedTextColor.GRAY));
                if (currentTier > 0) lore.add(Component.text("✔ Virtual storage active", NamedTextColor.GREEN));
            }
            case COLLECTION_AMOUNT -> {
                lore.add(Component.text("Collect more items per event.", NamedTextColor.GRAY));
                lore.add(Component.text("Tiers: 1→2→3→4 stacks at once.", NamedTextColor.GRAY));
                lore.add(Component.text("Current: " + (currentTier + 1) + " stack(s)", NamedTextColor.AQUA));
            }
            case RADIUS -> {
                int maxChunks = plugin.getConfig().getInt("upgrades.radius.max-chunks", 3);
                lore.add(Component.text("Collect from adjacent chunks.", NamedTextColor.GRAY));
                lore.add(Component.text("Each tier adds +1 chunk radius.", NamedTextColor.GRAY));
                lore.add(Component.text("Current radius: " + Math.min(currentTier, maxChunks) + " chunk(s)", NamedTextColor.AQUA));
            }
        }
    }

    private String configKey(UpgradeType type) {
        return switch (type) {
            case VOID_FILTER -> "upgrades.void-filter";
            case CAPACITY -> "upgrades.capacity";
            case COLLECTION_AMOUNT -> "upgrades.collection-amount";
            case RADIUS -> "upgrades.radius";
        };
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

        // Back button
        if (slot == 49) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getHopperGUI().openGUI(player, loc), 1L);
            return;
        }

        UpgradeType type = slotToUpgrade(slot);
        if (type == null) return;

        VaultHook vault = plugin.getVaultHook();
        if (vault == null) {
            player.sendMessage(Component.text("Vault economy is not installed.", NamedTextColor.RED));
            return;
        }

        List<Double> costs = plugin.getConfig().getDoubleList(configKey(type) + ".cost-per-tier");
        int currentTier = data.upgrades.getOrDefault(type, 0);
        if (currentTier >= costs.size()) {
            player.sendMessage(Component.text("This upgrade is already at max tier!", NamedTextColor.GOLD));
            return;
        }

        // Enforce radius server-wide cap
        if (type == UpgradeType.RADIUS) {
            int maxChunks = plugin.getConfig().getInt("upgrades.radius.max-chunks", 3);
            if (currentTier >= maxChunks) {
                player.sendMessage(Component.text("The server cap for Radius upgrades is " + maxChunks + " chunks.", NamedTextColor.RED));
                return;
            }
        }

        double cost = costs.get(currentTier);
        if (!vault.has(player, cost)) {
            player.sendMessage(Component.text("Insufficient funds! You need " + vault.format(cost) + ".", NamedTextColor.RED));
            return;
        }

        vault.withdraw(player, cost);
        data.upgrades.put(type, currentTier + 1);
        ChunkHopperEffects.modeChanged(player);
        player.sendMessage(Component.text("Upgraded " + type.name().replace("_", " ") + " to tier " + (currentTier + 1) + "!", NamedTextColor.GREEN));

        // Refresh GUI
        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> openGUI(player, loc), 1L);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;
        if (!event.getView().title().equals(TITLE)) return;
        player.removeMetadata("chunkhopper_upgrade_loc", plugin);
    }

    private UpgradeType slotToUpgrade(int slot) {
        return switch (slot) {
            case 20 -> UpgradeType.VOID_FILTER;
            case 24 -> UpgradeType.CAPACITY;
            case 29 -> UpgradeType.COLLECTION_AMOUNT;
            case 33 -> UpgradeType.RADIUS;
            default -> null;
        };
    }

    private Location getTrackedLoc(Player player) {
        if (!player.hasMetadata("chunkhopper_upgrade_loc")) return null;
        List<MetadataValue> meta = player.getMetadata("chunkhopper_upgrade_loc");
        if (meta.isEmpty()) return null;
        Object val = meta.get(0).value();
        return val instanceof Location ? (Location) val : null;
    }
}
