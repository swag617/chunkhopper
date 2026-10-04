package com.swag.chunkhopper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained equivalent of SwagAPI's {@code IConfigMigrationService}, for plugins (like this
 * one) that have no SwagAPI dependency of their own.
 *
 * <p>Fixes the same real gap in {@code JavaPlugin#saveDefaultConfig()}: that call only writes the
 * bundled {@code config.yml} to disk the very first time a plugin starts. On every later boot —
 * including after a jar update that adds brand-new config sections — it is a no-op, so an
 * existing install never receives new keys a developer added to the bundled default.
 *
 * <p>{@link #migrate(JavaPlugin, String)} recursively walks the plugin's bundled default YAML
 * resource and adds any key missing from the on-disk file, at any nesting depth, while never
 * touching a key the on-disk file already has (even if its value differs from the bundled
 * default — an admin's customization is never overwritten). A list value is always treated as a
 * single atomic value, never merged element-by-element. Nothing is written back to disk unless at
 * least one key was actually added.
 */
final class ConfigMigrationUtil {

    private ConfigMigrationUtil() {
    }

    /** Migrates {@code <dataFolder>/config.yml} against the bundled {@code config.yml} resource. */
    static List<String> migrate(JavaPlugin plugin) {
        return migrate(plugin, "config.yml");
    }

    /**
     * Migrates {@code <dataFolder>/<fileName>} against the bundled resource of the same name.
     *
     * @param plugin   the plugin supplying both the bundled resource (its jar) and the on-disk
     *                 target (its data folder).
     * @param fileName resource path in the jar / relative path under the data folder.
     * @return the dot-notation path of every key that was added; empty if nothing changed (and,
     *         in that case, the file was not re-written).
     */
    static List<String> migrate(JavaPlugin plugin, String fileName) {
        List<String> added = new ArrayList<>();

        File onDiskFile = new File(plugin.getDataFolder(), fileName);
        if (!onDiskFile.exists()) {
            // Fresh install — plugin.saveResource/saveDefaultConfig already handles this case
            // elsewhere; nothing to migrate.
            return added;
        }

        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream == null) {
            return added;
        }

        FileConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().warning("ConfigMigrationUtil: failed to read bundled " + fileName + ": " + e.getMessage());
            return added;
        }

        FileConfiguration onDisk = YamlConfiguration.loadConfiguration(onDiskFile);

        boolean changed = mergeMissingKeys(defaults, onDisk, "", added);

        if (changed) {
            try {
                onDisk.save(onDiskFile);
                plugin.getLogger().info("ConfigMigrationUtil: added " + added.size()
                        + " missing key(s) to " + fileName + ": " + added);
            } catch (Exception e) {
                plugin.getLogger().warning("ConfigMigrationUtil: failed to save " + fileName + ": " + e.getMessage());
            }
        }

        return added;
    }

    /**
     * Recursively copies any key present in {@code defaults} but absent from {@code target}.
     * Sections are recursed into; any other value type (including lists) is copied atomically as
     * a whole, only when the key is missing on-disk entirely.
     *
     * @return true if at least one key was added anywhere in this subtree.
     */
    private static boolean mergeMissingKeys(ConfigurationSection defaults, ConfigurationSection target,
                                              String pathPrefix, List<String> added) {
        boolean changed = false;

        for (String key : defaults.getKeys(false)) {
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            Object defaultValue = defaults.get(key);

            if (defaultValue instanceof ConfigurationSection defaultSection) {
                if (!target.isConfigurationSection(key)) {
                    if (target.isSet(key)) {
                        // On-disk has a non-section value where the default now expects a
                        // section (a genuine structural conflict) — leave the admin's value
                        // alone rather than guessing; skip this subtree entirely.
                        continue;
                    }
                    target.createSection(key);
                }
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (mergeMissingKeys(defaultSection, targetSection, fullPath, added)) {
                    changed = true;
                }
            } else if (!target.isSet(key)) {
                target.set(key, defaultValue);
                added.add(fullPath);
                changed = true;
            }
        }

        return changed;
    }
}
