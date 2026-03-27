package com.swag.chunkhopper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChunkHopperManager {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final ChunkHopperPlugin plugin;
    private final File file;
    private ItemStack hopperItem;
    private final NamespacedKey chunkHopperKey;
    private final NamespacedKey hopperDataKey;
    private final Map<Location, HopperData> hopperData = new HashMap<>();
    private final Map<String, List<Location>> chunkIndex = new HashMap<>();
    private final Map<String, Map<String, Object>> unresolvedEntries = new HashMap<>();

    public ChunkHopperManager(ChunkHopperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hoppers.yml");
        this.chunkHopperKey = new NamespacedKey(plugin, "chunk_hopper");
        this.hopperDataKey = new NamespacedKey(plugin, "hopper_data");
        loadConfig();
        loadAllFromFile();
    }

    // ---------------------------------------------------------------
    // Config / item building
    // ---------------------------------------------------------------

    public void loadConfig() {
        hopperItem = buildHopperItem(1);
    }

    /**
     * Central factory for the Chunk Hopper ItemStack.
     * Reads name, lore, glow from config; sets PDC tag; sets amount.
     */
    public ItemStack buildHopperItem(int amount) {
        String materialName = plugin.getConfig().getString("hopper-item.material", "HOPPER");
        Material mat = Material.getMaterial(materialName);
        if (mat == null) mat = Material.HOPPER;

        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Name
            String rawName = plugin.getConfig().getString("hopper-item.name",
                    "&x&7&D&1&4&B&E&lC&x&6&5&3&4&C&1&lh&x&4&D&4&4&B&F&lu&x&3&8&4&F&B&B&ln&x&2&7&5&7&B&3&lk " +
                    "&x&2&B&6&1&9&D&lH&x&3&1&6&5&A&0&lo&x&3&7&6&A&A&3&lp&x&3&D&6&E&A&6&lp&x&4&2&7&3&A&9&le&x&4&8&7&7&A&C&lr");
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(rawName));

            // Lore
            List<String> configLore = plugin.getConfig().getStringList("hopper-item.lore");
            List<Component> loreComponents = new ArrayList<>();
            if (configLore.isEmpty()) {
                loreComponents.add(Component.text("--------------------").color(NamedTextColor.DARK_GRAY));
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Collects items throughout the chunk."));
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Right-click to customize filters."));
                loreComponents.add(Component.text("--------------------").color(NamedTextColor.DARK_GRAY));
            } else {
                for (String line : configLore) {
                    loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
                }
            }
            meta.lore(loreComponents);

            // Glow
            if (plugin.getConfig().getBoolean("hopper-item.glow", true)) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            // PDC tag
            meta.getPersistentDataContainer().set(chunkHopperKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Builds the Chunk Hopper item for an existing placed hopper.
     * Appends upgrade tier info to the lore if any upgrades have been purchased.
     */
    public ItemStack buildHopperItem(int amount, HopperData data) {
        ItemStack item = buildHopperItem(amount);
        if (data == null) return item;

        boolean anyUpgrade = data.upgrades.values().stream().anyMatch(t -> t > 0);
        if (!anyUpgrade) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Upgrades", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Map.Entry<UpgradeType, Integer> entry : data.upgrades.entrySet()) {
            if (entry.getValue() > 0) {
                String name = entry.getKey().name().replace("_", " ");
                lore.add(Component.text("  " + name + ": Tier " + entry.getValue(), NamedTextColor.GRAY));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ---------------------------------------------------------------
    // Color translation (hex + & codes)
    // ---------------------------------------------------------------

    private String translate(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : hex.toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString().replace("&", "\u00a7");
    }

    // ---------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------

    public ItemStack getHopperItem() {
        return hopperItem;
    }

    public NamespacedKey getChunkHopperKey() {
        return chunkHopperKey;
    }

    public NamespacedKey getHopperDataKey() {
        return hopperDataKey;
    }

    // ---------------------------------------------------------------
    // Chunk key / location helpers
    // ---------------------------------------------------------------

    public String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ();
    }

    private Location fix(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private String serializeLoc(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserializeLoc(String s) {
        try {
            String[] parts = s.split(",");
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Data access
    // ---------------------------------------------------------------

    public boolean canPlaceHopper(Chunk chunk) {
        int max = plugin.getConfig().getInt("max-hoppers-per-chunk", 1);
        if (max < 0) return true;
        String key = getChunkKey(chunk);
        return chunkIndex.getOrDefault(key, Collections.emptyList()).size() < max;
    }

    public void addHopper(Location loc, UUID owner) {
        loc = fix(loc);
        String chunkKey = getChunkKey(loc.getChunk());
        HopperData data = new HopperData(owner);
        hopperData.put(loc, data);
        chunkIndex.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(loc);
    }

    public void addHopperWithData(Location loc, HopperData data) {
        loc = fix(loc);
        String chunkKey = getChunkKey(loc.getChunk());
        hopperData.put(loc, data);
        chunkIndex.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(loc);
    }

    public void removeHopper(Location loc) {
        loc = fix(loc);
        String chunkKey = getChunkKey(loc.getChunk());
        hopperData.remove(loc);
        List<Location> list = chunkIndex.get(chunkKey);
        if (list != null) {
            list.remove(loc);
            if (list.isEmpty()) {
                chunkIndex.remove(chunkKey);
            }
        }
    }

    public UUID getOwner(Location loc) {
        HopperData data = hopperData.get(fix(loc));
        return data != null ? data.owner : null;
    }

    public boolean isChunkHopper(Location loc) {
        return hopperData.containsKey(fix(loc));
    }

    public HopperData getData(Location loc) {
        return hopperData.get(fix(loc));
    }

    public List<Location> getHoppersInChunk(Chunk chunk) {
        List<Location> live = chunkIndex.get(getChunkKey(chunk));
        return live != null ? new ArrayList<>(live) : Collections.emptyList();
    }

    public List<Location> getAllHopperLocations() {
        return new ArrayList<>(hopperData.keySet());
    }

    public void recordCollection(Location loc, Material mat, int amount) {
        HopperData data = hopperData.get(fix(loc));
        if (data == null) return;
        data.totalCollected += amount;
        data.sessionCollected += amount;
        data.lastCollectedTimestamp = System.currentTimeMillis();
        data.itemCounts.merge(mat, (long) amount, Long::sum);
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    public void saveAllToFile() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();

            for (Map.Entry<Location, HopperData> entry : hopperData.entrySet()) {
                String key = serializeLoc(entry.getKey());
                HopperData data = entry.getValue();

                yaml.set(key + ".owner", data.owner != null ? data.owner.toString() : null);
                yaml.set(key + ".mode", data.mode.name());
                yaml.set(key + ".filters", data.filterItems.stream().map(Material::name).toList());
                yaml.set(key + ".totalCollected", data.totalCollected);
                yaml.set(key + ".sessionCollected", data.sessionCollected);
                yaml.set(key + ".lastCollected", data.lastCollectedTimestamp);

                Map<String, Object> counts = new HashMap<>();
                for (Map.Entry<Material, Long> ce : data.itemCounts.entrySet()) {
                    counts.put(ce.getKey().name(), ce.getValue());
                }
                yaml.set(key + ".itemCounts", counts);

                // upgrades
                Map<String, Object> upgradeMap = new HashMap<>();
                for (Map.Entry<UpgradeType, Integer> ue : data.upgrades.entrySet()) {
                    upgradeMap.put(ue.getKey().name(), ue.getValue());
                }
                yaml.set(key + ".upgrades", upgradeMap);

                // void filter
                yaml.set(key + ".voidFilter", data.voidFilter.stream().map(Material::name).toList());

                // virtual storage — each entry: base64(BukkitObjectOutputStream)|count
                List<String> vsEntries = new ArrayList<>();
                for (VirtualEntry ve : data.virtualStorage) {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
                            oos.writeObject(ve.template);
                        }
                        String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                        vsEntries.add(b64 + "|" + ve.count);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to serialize virtual entry: " + ex.getMessage());
                    }
                }
                yaml.set(key + ".virtualStorage", vsEntries);
            }

            // Preserve unresolved entries (worlds that were not loaded)
            for (Map.Entry<String, Map<String, Object>> entry : unresolvedEntries.entrySet()) {
                for (Map.Entry<String, Object> field : entry.getValue().entrySet()) {
                    yaml.set(entry.getKey() + "." + field.getKey(), field.getValue());
                }
            }

            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save hopper data: " + e.getMessage());
        }
    }

    public void loadAllFromFile() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        for (String key : yaml.getKeys(false)) {
            Location loc = deserializeLoc(key);
            if (loc == null) {
                // World not loaded — preserve as unresolved
                ConfigurationSection section = yaml.getConfigurationSection(key);
                if (section != null) {
                    unresolvedEntries.put(key, section.getValues(true));
                }
                continue;
            }

            try {
                UUID owner = yaml.contains(key + ".owner")
                        ? UUID.fromString(yaml.getString(key + ".owner"))
                        : null;

                HopperData data = new HopperData(owner);
                data.mode = FilterMode.valueOf(yaml.getString(key + ".mode", "ALL"));

                for (String matName : yaml.getStringList(key + ".filters")) {
                    try {
                        data.filterItems.add(Material.valueOf(matName));
                    } catch (Exception ignored) {}
                }

                data.totalCollected = yaml.getLong(key + ".totalCollected", 0L);
                data.sessionCollected = yaml.getLong(key + ".sessionCollected", 0L);
                data.lastCollectedTimestamp = yaml.getLong(key + ".lastCollected", 0L);

                ConfigurationSection countsSection = yaml.getConfigurationSection(key + ".itemCounts");
                if (countsSection != null) {
                    countsSection.getValues(false).forEach((matName, val) -> {
                        try {
                            data.itemCounts.put(Material.valueOf(matName), ((Number) val).longValue());
                        } catch (Exception ignored) {}
                    });
                }

                // upgrades
                ConfigurationSection upgradesSection = yaml.getConfigurationSection(key + ".upgrades");
                if (upgradesSection != null) {
                    upgradesSection.getValues(false).forEach((typeName, val) -> {
                        try {
                            data.upgrades.put(UpgradeType.valueOf(typeName), ((Number) val).intValue());
                        } catch (Exception ignored) {}
                    });
                }

                // void filter
                for (String matName : yaml.getStringList(key + ".voidFilter")) {
                    try { data.voidFilter.add(Material.valueOf(matName)); } catch (Exception ignored) {}
                }

                // virtual storage (new format: list of "base64|count" strings)
                for (String vsEntry : yaml.getStringList(key + ".virtualStorage")) {
                    String[] parts = vsEntry.split("\\|", 2);
                    if (parts.length != 2) continue;
                    try {
                        byte[] bytes = Base64.getDecoder().decode(parts[0]);
                        ItemStack template;
                        try (BukkitObjectInputStream ois = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                            template = (ItemStack) ois.readObject();
                        }
                        long count = Long.parseLong(parts[1]);
                        data.virtualStorage.add(new VirtualEntry(template, count));
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to deserialize virtual entry: " + ex.getMessage());
                    }
                }

                hopperData.put(loc, data);
                chunkIndex.computeIfAbsent(getChunkKey(loc.getChunk()), k -> new ArrayList<>()).add(loc);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load hopper at " + key + ": " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Upgrade / radius helpers
    // ---------------------------------------------------------------

    /**
     * Returns all hoppers that should collect an item spawning in the given chunk.
     * Scans all chunks within the server-wide radius cap, then includes a hopper
     * only if the item's chunk falls within that hopper's own radius tier.
     * This is the correct direction: hopper-radius covers adjacent chunks, not
     * the other way around.
     */
    public List<Location> getHoppersForItem(Chunk itemChunk) {
        int serverCap = plugin.getConfig().getInt("upgrades.radius.max-chunks", 3);
        int itemCX = itemChunk.getX();
        int itemCZ = itemChunk.getZ();
        String worldName = itemChunk.getWorld().getName();
        List<Location> result = new ArrayList<>();
        for (int dx = -serverCap; dx <= serverCap; dx++) {
            for (int dz = -serverCap; dz <= serverCap; dz++) {
                int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
                String key = worldName + "," + (itemCX + dx) + "," + (itemCZ + dz);
                List<Location> list = chunkIndex.get(key);
                if (list == null) continue;
                for (Location hopperLoc : new ArrayList<>(list)) {
                    HopperData d = hopperData.get(hopperLoc);
                    if (d == null) continue;
                    int hopperRadius = Math.min(d.upgrades.getOrDefault(UpgradeType.RADIUS, 0), serverCap);
                    if (chunkDist <= hopperRadius) result.add(hopperLoc);
                }
            }
        }
        return result;
    }

    /** @deprecated Use {@link #getHoppersForItem(Chunk)} for radius-aware lookup. */
    public List<Location> getHoppersInRadius(Chunk center, int extraChunks) {
        List<Location> result = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();
        String worldName = center.getWorld().getName();
        for (int dx = -extraChunks; dx <= extraChunks; dx++) {
            for (int dz = -extraChunks; dz <= extraChunks; dz++) {
                String key = worldName + "," + (cx + dx) + "," + (cz + dz);
                List<Location> list = chunkIndex.get(key);
                if (list != null) result.addAll(list);
            }
        }
        return result;
    }

    public void addToVirtualStorage(Location loc, ItemStack item, long amount) {
        HopperData data = hopperData.get(fix(loc));
        if (data == null) return;
        long cap = plugin.getConfig().getLong("upgrades.capacity.max-virtual-storage", 100000L);
        for (VirtualEntry entry : data.virtualStorage) {
            if (entry.template.isSimilar(item)) {
                entry.count = Math.min(entry.count + amount, cap);
                return;
            }
        }
        data.virtualStorage.add(new VirtualEntry(item, Math.min(amount, cap)));
    }

    public long withdrawFromVirtualStorage(Location loc, ItemStack template, long amount) {
        HopperData data = hopperData.get(fix(loc));
        if (data == null) return 0;
        Iterator<VirtualEntry> it = data.virtualStorage.iterator();
        while (it.hasNext()) {
            VirtualEntry entry = it.next();
            if (entry.template.isSimilar(template)) {
                long taken = Math.min(entry.count, amount);
                entry.count -= taken;
                if (entry.count <= 0) it.remove();
                return taken;
            }
        }
        return 0;
    }

    public boolean isVirtualStorageFull(Location loc) {
        HopperData data = hopperData.get(fix(loc));
        if (data == null) return true;
        long cap = plugin.getConfig().getLong("upgrades.capacity.max-virtual-storage", 100000L);
        long total = data.virtualStorage.stream().mapToLong(e -> e.count).sum();
        return total >= cap;
    }

    // ---------------------------------------------------------------
    // Item PDC serialization
    // ---------------------------------------------------------------

    public String serializeDataToString(HopperData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (data.owner != null) yaml.set("owner", data.owner.toString());
        yaml.set("mode", data.mode.name());
        yaml.set("filters", data.filterItems.stream().map(Material::name).toList());
        yaml.set("void-filter", new ArrayList<>(data.voidFilter).stream().map(Material::name).toList());
        yaml.set("total-collected", data.totalCollected);

        Map<String, Object> upgradeMap = new HashMap<>();
        for (Map.Entry<UpgradeType, Integer> e : data.upgrades.entrySet()) {
            upgradeMap.put(e.getKey().name(), e.getValue());
        }
        yaml.set("upgrades", upgradeMap);

        Map<String, Object> countsMap = new HashMap<>();
        for (Map.Entry<Material, Long> e : data.itemCounts.entrySet()) {
            countsMap.put(e.getKey().name(), e.getValue());
        }
        yaml.set("item-counts", countsMap);

        return yaml.saveToString();
    }

    public HopperData deserializeDataFromString(String s) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(s);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize hopper data from item: " + e.getMessage());
            return null;
        }

        UUID owner = null;
        try {
            if (yaml.contains("owner")) owner = UUID.fromString(yaml.getString("owner"));
        } catch (Exception ignored) {}

        HopperData data = new HopperData(owner);
        try { data.mode = FilterMode.valueOf(yaml.getString("mode", "ALL")); } catch (Exception ignored) {}

        for (String matName : yaml.getStringList("filters")) {
            try { data.filterItems.add(Material.valueOf(matName)); } catch (Exception ignored) {}
        }
        for (String matName : yaml.getStringList("void-filter")) {
            try { data.voidFilter.add(Material.valueOf(matName)); } catch (Exception ignored) {}
        }

        data.totalCollected = yaml.getLong("total-collected", 0);

        ConfigurationSection upgradesSection = yaml.getConfigurationSection("upgrades");
        if (upgradesSection != null) {
            upgradesSection.getValues(false).forEach((typeName, val) -> {
                try { data.upgrades.put(UpgradeType.valueOf(typeName), ((Number) val).intValue()); } catch (Exception ignored) {}
            });
        }

        ConfigurationSection countsSection = yaml.getConfigurationSection("item-counts");
        if (countsSection != null) {
            countsSection.getValues(false).forEach((matName, val) -> {
                try { data.itemCounts.put(Material.valueOf(matName), ((Number) val).longValue()); } catch (Exception ignored) {}
            });
        }

        return data;
    }

    // ---------------------------------------------------------------
    // Inner classes
    // ---------------------------------------------------------------

    public static class VirtualEntry {
        public ItemStack template; // amount always 1, full meta preserved
        public long count;

        public VirtualEntry(ItemStack t, long c) {
            this.template = t.clone();
            this.template.setAmount(1);
            this.count = c;
        }
    }

    public static class HopperData {
        public UUID owner;
        public FilterMode mode = FilterMode.ALL;
        public List<Material> filterItems = new ArrayList<>();
        public long totalCollected = 0L;
        public long sessionCollected = 0L;
        public long lastCollectedTimestamp = 0L;
        public Map<Material, Long> itemCounts = new HashMap<>();
        public Map<UpgradeType, Integer> upgrades = new EnumMap<>(UpgradeType.class);
        public Set<Material> voidFilter = new HashSet<>();
        public List<VirtualEntry> virtualStorage = new ArrayList<>();

        public HopperData(UUID owner) {
            this.owner = owner;
        }
    }
}
