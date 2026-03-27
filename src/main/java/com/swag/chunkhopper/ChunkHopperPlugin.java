package com.swag.chunkhopper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;


public class ChunkHopperPlugin extends JavaPlugin {

    private static ChunkHopperPlugin instance;

    private ChunkHopperManager hopperManager;
    private ChunkHopperGUI hopperGUI;
    private ChunkHopperListener hopperListener;
    private GPHook gpHook;
    private VaultHook vaultHook;
    private UpgradeGUI upgradeGUI;
    private VoidFilterGUI voidFilterGUI;
    private InventoryGUI inventoryGUI;
    private int autosaveTaskId = -1;
    private int transferTaskId = -1;

    @Override
    public void onEnable() {
        instance = this;
        migrateConfig();

        hopperManager = new ChunkHopperManager(this);
        hopperGUI = new ChunkHopperGUI(this, hopperManager);
        hopperListener = new ChunkHopperListener(this, hopperManager);

        upgradeGUI = new UpgradeGUI(this, hopperManager);
        voidFilterGUI = new VoidFilterGUI(this, hopperManager);
        inventoryGUI = new InventoryGUI(this, hopperManager);

        getServer().getPluginManager().registerEvents(hopperListener, this);
        getServer().getPluginManager().registerEvents(hopperGUI, this);
        getServer().getPluginManager().registerEvents(upgradeGUI, this);
        getServer().getPluginManager().registerEvents(voidFilterGUI, this);
        getServer().getPluginManager().registerEvents(inventoryGUI, this);

        getCommand("chunkhopper").setExecutor(new ChunkHopperCommand(this));

        startAutosaveTask();
        startTransferTask();

        if (Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) {
            gpHook = new GPHook();
            getLogger().info("GriefPrevention detected.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp != null) {
                vaultHook = new VaultHook(rsp.getProvider());
                getLogger().info("Vault economy hooked.");
            } else {
                getLogger().warning("Vault found but no economy provider registered.");
            }
        }

        getLogger().info("ChunkHopper plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (hopperManager != null) {
            hopperManager.saveAllToFile();
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (autosaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
        }
        if (transferTaskId != -1) {
            Bukkit.getScheduler().cancelTask(transferTaskId);
        }
        getLogger().info("ChunkHopper plugin disabled!");
    }

    private void migrateConfig() {
        saveDefaultConfig();
        int version = getConfig().getInt("config-version", 0);
        if (version < 1) {
            if (!getConfig().isSet("upgrades")) {
                getConfig().set("upgrades.void-filter.cost-per-tier", java.util.List.of(500.0, 1000.0, 1500.0, 2000.0, 2500.0));
                getConfig().set("upgrades.capacity.cost-per-tier", java.util.List.of(1000.0, 2500.0, 5000.0));
                getConfig().set("upgrades.capacity.max-virtual-storage", 100000);
                getConfig().set("upgrades.collection-amount.cost-per-tier", java.util.List.of(750.0, 1500.0, 3000.0));
                getConfig().set("upgrades.radius.cost-per-tier", java.util.List.of(2000.0, 4000.0, 8000.0));
                getConfig().set("upgrades.radius.max-chunks", 3);
            }
            if (!getConfig().isSet("effects.enabled")) {
                getConfig().set("effects.enabled", true);
            }
            getConfig().set("config-version", 1);
            saveConfig();
        }
    }

    private void startAutosaveTask() {
        int intervalTicks = getConfig().getInt("autosave-interval", 30) * 20;
        autosaveTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> hopperManager.saveAllToFile(), intervalTicks, intervalTicks).getTaskId();
    }

    private void startTransferTask() {
        transferTaskId = Bukkit.getScheduler().runTaskTimer(this, this::runTransfers, 4L, 8L).getTaskId();
    }

    private void runTransfers() {
        for (Location loc : hopperManager.getAllHopperLocations()) {
            ChunkHopperManager.HopperData data = hopperManager.getData(loc);
            if (data == null) continue;

            int tier = data.upgrades.getOrDefault(UpgradeType.COLLECTION_AMOUNT, 0);
            if (tier == 0) continue; // vanilla handles tier 0

            Block block = loc.getBlock();
            if (block.getType() != Material.HOPPER) continue;

            // Find the container the hopper is pointing at (output face)
            org.bukkit.block.data.type.Hopper hopperBlockData =
                    (org.bukkit.block.data.type.Hopper) block.getBlockData();
            Block targetBlock = block.getRelative(hopperBlockData.getFacing());
            if (!(targetBlock.getState() instanceof Container container)) continue;

            int transferAmount = tier + 1; // tier 1=2, tier 2=3, tier 3=4 items per 8 ticks

            if (data.upgrades.getOrDefault(UpgradeType.CAPACITY, 0) > 0) {
                // Transfer from virtual storage (preserves full NBT/PDC)
                for (ChunkHopperManager.VirtualEntry entry : new java.util.ArrayList<>(data.virtualStorage)) {
                    if (entry.count <= 0) continue;

                    int toMove = (int) Math.min(entry.count, transferAmount);
                    ItemStack moving = entry.template.clone();
                    moving.setAmount(toMove);
                    java.util.HashMap<Integer, ItemStack> leftover = container.getInventory().addItem(moving);
                    int actualMoved = toMove - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                    if (actualMoved > 0) hopperManager.withdrawFromVirtualStorage(loc, entry.template, actualMoved);
                    break; // one entry per tick, like vanilla
                }
            } else {
                // Transfer from physical hopper inventory
                org.bukkit.block.Hopper hopperState = (org.bukkit.block.Hopper) block.getState();
                ItemStack[] contents = hopperState.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack stack = contents[i];
                    if (stack == null || stack.getType() == Material.AIR) continue;

                    int toMove = Math.min(stack.getAmount(), transferAmount);
                    ItemStack moving = stack.clone();
                    moving.setAmount(toMove);

                    java.util.HashMap<Integer, ItemStack> leftover = container.getInventory().addItem(moving);
                    int actualMoved = toMove - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                    if (actualMoved > 0) {
                        int remaining = stack.getAmount() - actualMoved;
                        hopperState.getInventory().setItem(i, remaining <= 0 ? null : new ItemStack(stack.getType(), remaining));
                    }
                    break; // one slot per tick, like vanilla
                }
            }
        }
    }

    public static ChunkHopperPlugin getInstance() {
        return instance;
    }

    public ChunkHopperManager getManager() {
        return hopperManager;
    }

    public ChunkHopperGUI getHopperGUI() {
        return hopperGUI;
    }

    public GPHook getGPHook() {
        return gpHook;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public UpgradeGUI getUpgradeGUI() {
        return upgradeGUI;
    }

    public VoidFilterGUI getVoidFilterGUI() {
        return voidFilterGUI;
    }

    public InventoryGUI getInventoryGUI() {
        return inventoryGUI;
    }
}
