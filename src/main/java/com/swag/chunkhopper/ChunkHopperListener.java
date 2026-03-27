package com.swag.chunkhopper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChunkHopperListener implements Listener {

    private final ChunkHopperPlugin plugin;
    private final ChunkHopperManager manager;
    private final NamespacedKey chunkHopperKey;

    /** Throttle set: locations whose owner has already been notified the hopper is full */
    private final Set<Location> notifiedFull = new HashSet<>();

    public ChunkHopperListener(ChunkHopperPlugin plugin, ChunkHopperManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.chunkHopperKey = new NamespacedKey(plugin, "chunk_hopper");
    }

    // ---------------------------------------------------------------
    // Place
    // ---------------------------------------------------------------

    @EventHandler
    public void onHopperPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isChunkHopperItem(item)) return;

        Block block = event.getBlockPlaced();
        if (!manager.canPlaceHopper(block.getChunk())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(colorize(plugin.getConfig().getString(
                    "messages.hopper-limit-reached", "&cLimit reached!")));
            return;
        }

        // Check if the placed item has serialized hopper data (from a previous break)
        String serialized = null;
        ItemMeta placedMeta = item.getItemMeta();
        if (placedMeta != null && placedMeta.getPersistentDataContainer()
                .has(manager.getHopperDataKey(), org.bukkit.persistence.PersistentDataType.STRING)) {
            serialized = placedMeta.getPersistentDataContainer()
                    .get(manager.getHopperDataKey(), org.bukkit.persistence.PersistentDataType.STRING);
        }

        if (serialized != null) {
            ChunkHopperManager.HopperData restoredData = manager.deserializeDataFromString(serialized);
            if (restoredData != null) {
                manager.addHopperWithData(block.getLocation(), restoredData);
            } else {
                manager.addHopper(block.getLocation(), event.getPlayer().getUniqueId());
            }
        } else {
            manager.addHopper(block.getLocation(), event.getPlayer().getUniqueId());
        }

        event.getPlayer().sendMessage(colorize(plugin.getConfig().getString(
                "messages.hopper-placed", "&aHopper placed!")));
    }

    // ---------------------------------------------------------------
    // Break
    // ---------------------------------------------------------------

    @EventHandler
    public void onHopperBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER) return;

        Location loc = block.getLocation();
        if (!manager.isChunkHopper(loc)) return;

        Player player = event.getPlayer();
        UUID ownerId = manager.getOwner(loc);
        GPHook gpHook = plugin.getGPHook();

        boolean allowed = player.hasPermission("chunkhopper.admin")
                || (ownerId != null && ownerId.equals(player.getUniqueId()))
                || (gpHook != null && gpHook.hasBuildTrust(player, loc));

        if (allowed) {
            event.setCancelled(true);

            // Capture data BEFORE removing from manager
            ChunkHopperManager.HopperData data = manager.getData(loc);

            // Drop physical hopper inventory contents
            Hopper hopperState = (Hopper) block.getState();
            for (ItemStack content : hopperState.getInventory().getContents()) {
                if (content != null && content.getType() != Material.AIR) {
                    block.getWorld().dropItemNaturally(loc, content);
                }
            }
            hopperState.getInventory().clear();

            // Drop virtual storage contents (preserves full NBT/PDC)
            if (data != null && !data.virtualStorage.isEmpty()) {
                for (ChunkHopperManager.VirtualEntry entry : data.virtualStorage) {
                    long amount = entry.count;
                    while (amount > 0) {
                        int stackSize = (int) Math.min(amount, entry.template.getMaxStackSize());
                        ItemStack drop = entry.template.clone();
                        drop.setAmount(stackSize);
                        block.getWorld().dropItemNaturally(loc, drop);
                        amount -= stackSize;
                    }
                }
            }

            // Build the dropped hopper item, embedding serialized data in PDC
            ItemStack droppedHopper = manager.buildHopperItem(1, data);
            if (data != null) {
                String serialized = manager.serializeDataToString(data);
                ItemMeta hopperMeta = droppedHopper.getItemMeta();
                if (hopperMeta != null) {
                    hopperMeta.getPersistentDataContainer().set(
                            manager.getHopperDataKey(), org.bukkit.persistence.PersistentDataType.STRING, serialized);
                    droppedHopper.setItemMeta(hopperMeta);
                }
            }

            // Now remove from manager and destroy block
            manager.removeHopper(loc);
            notifiedFull.remove(loc);
            block.setType(Material.AIR);
            block.getWorld().dropItemNaturally(loc, droppedHopper);

            player.sendMessage(colorize(plugin.getConfig().getString(
                    "messages.hopper-removed", "&cHopper removed.")));
        } else {
            event.setCancelled(true);
            player.sendMessage(colorize(plugin.getConfig().getString(
                    "messages.no-permission", "&cYou are not allowed to break this hopper.")));
        }
    }

    // ---------------------------------------------------------------
    // Right-click interact
    // ---------------------------------------------------------------

    @EventHandler
    public void onHopperRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.HOPPER) return;

        Location loc = event.getClickedBlock().getLocation();
        if (!manager.isChunkHopper(loc)) return;

        Player player = event.getPlayer();
        if (!isPlayerTrustedForHopper(player, loc)) {
            event.setCancelled(true);
            player.sendMessage(colorize(plugin.getConfig().getString(
                    "messages.no-permission", "&cYou are not allowed to use this hopper.")));
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getHopperGUI().openGUI(player, loc));
    }

    // ---------------------------------------------------------------
    // Item spawn (collection)
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("instant-collection", true)) return;

        Item itemEntity = event.getEntity();
        ItemStack itemStack = itemEntity.getItemStack();
        Material itemType = itemStack.getType();

        List<Location> hoppers = manager.getHoppersForItem(itemEntity.getLocation().getChunk());
        if (hoppers.isEmpty()) return;

        // Void filter check — if any hopper in range voids this item type, cancel spawn
        for (Location hopperLoc : hoppers) {
            ChunkHopperManager.HopperData d = manager.getData(hopperLoc);
            if (d != null && d.voidFilter.contains(itemType)) {
                event.setCancelled(true);
                return;
            }
        }

        for (Location hopperLoc : hoppers) {
            Block block = hopperLoc.getBlock();
            if (block.getType() != Material.HOPPER) {
                manager.removeHopper(hopperLoc);
                continue;
            }

            ChunkHopperManager.HopperData data = manager.getData(hopperLoc);
            if (data == null) continue;

            boolean shouldCollect = switch (data.mode) {
                case ALL -> true;
                case WHITELIST -> data.filterItems.contains(itemType);
                case BLACKLIST -> !data.filterItems.contains(itemType);
            };
            if (!shouldCollect) continue;

            // Determine how many items to collect (collection amount upgrade)
            int collectionTier = data.upgrades.getOrDefault(UpgradeType.COLLECTION_AMOUNT, 0);
            int maxCollect = (collectionTier + 1) * 64;
            int collectAmount = Math.min(itemStack.getAmount(), maxCollect);

            if (data.upgrades.getOrDefault(UpgradeType.CAPACITY, 0) > 0) {
                // Virtual storage path
                if (!manager.isVirtualStorageFull(hopperLoc)) {
                    manager.addToVirtualStorage(hopperLoc, itemStack, collectAmount);
                    manager.recordCollection(hopperLoc, itemType, collectAmount);
                    ChunkHopperEffects.itemCollected(hopperLoc);
                    notifiedFull.remove(hopperLoc);

                    int remaining = itemStack.getAmount() - collectAmount;
                    if (remaining <= 0) {
                        event.setCancelled(true);
                    } else {
                        ItemStack newStack = itemStack.clone();
                        newStack.setAmount(remaining);
                        itemEntity.setItemStack(newStack);
                        itemStack = newStack;
                    }
                    return;
                } else {
                    notifyFull(hopperLoc, data);
                }
            } else {
                // Physical hopper inventory path
                Hopper hopperState = (Hopper) block.getState();
                Inventory inv = hopperState.getInventory();

                ItemStack toAdd = itemStack.clone();
                toAdd.setAmount(collectAmount);
                HashMap<Integer, ItemStack> leftover = inv.addItem(toAdd);

                if (leftover.isEmpty()) {
                    manager.recordCollection(hopperLoc, itemType, collectAmount);
                    ChunkHopperEffects.itemCollected(hopperLoc);
                    notifiedFull.remove(hopperLoc);

                    int remaining = itemStack.getAmount() - collectAmount;
                    if (remaining <= 0) {
                        event.setCancelled(true);
                    } else {
                        ItemStack newStack = itemStack.clone();
                        newStack.setAmount(remaining);
                        itemEntity.setItemStack(newStack);
                        itemStack = newStack;
                    }
                    return;
                } else {
                    int actuallyAdded = collectAmount - leftover.values().iterator().next().getAmount();
                    if (actuallyAdded > 0) {
                        manager.recordCollection(hopperLoc, itemType, actuallyAdded);
                    }
                    notifyFull(hopperLoc, data);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Vanilla transfer cancellation (for upgraded COLLECTION_AMOUNT hoppers)
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // For chunk hoppers with COLLECTION_AMOUNT tier > 0, the transfer task
        // handles item movement. Cancel vanilla transfer to avoid double-moving.
        if (!(event.getSource().getHolder() instanceof org.bukkit.block.Hopper hopper)) return;
        Location loc = hopper.getBlock().getLocation();
        if (!manager.isChunkHopper(loc)) return;
        ChunkHopperManager.HopperData data = manager.getData(loc);
        if (data == null) return;
        if (data.upgrades.getOrDefault(UpgradeType.COLLECTION_AMOUNT, 0) > 0) {
            event.setCancelled(true);
        }
    }

    private void notifyFull(Location hopperLoc, ChunkHopperManager.HopperData data) {
        UUID ownerId = data.owner;
        if (ownerId != null && !notifiedFull.contains(hopperLoc)) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                String msg = plugin.getConfig().getString("messages.hopper-full",
                        "&eYour Chunk Hopper at &f{x}&e, &f{y}&e, &f{z}&e is full!")
                        .replace("{x}", String.valueOf(hopperLoc.getBlockX()))
                        .replace("{y}", String.valueOf(hopperLoc.getBlockY()))
                        .replace("{z}", String.valueOf(hopperLoc.getBlockZ()));
                owner.sendMessage(colorize(msg));
            }
            notifiedFull.add(hopperLoc);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    public boolean isPlayerTrustedForHopper(Player player, Location loc) {
        if (player.hasPermission("chunkhopper.admin")) return true;
        UUID ownerId = manager.getOwner(loc);
        if (ownerId == null || ownerId.equals(player.getUniqueId())) return true;
        GPHook gpHook = plugin.getGPHook();
        return gpHook != null && gpHook.hasContainerTrust(player, loc);
    }

    public boolean isChunkHopperItem(ItemStack item) {
        if (item == null || item.getType() != Material.HOPPER) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (meta.getPersistentDataContainer().has(chunkHopperKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
            return true;
        }
        // Legacy fallback: name contains "Chunk Hopper"
        if (meta.hasDisplayName() && meta.hasLore()) {
            String name = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(meta.displayName());
            return name.contains("Chunk Hopper");
        }
        return false;
    }

    private String colorize(String message) {
        return message.replace("&", "\u00a7");
    }
}
