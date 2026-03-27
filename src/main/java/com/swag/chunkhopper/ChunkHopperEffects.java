package com.swag.chunkhopper;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class ChunkHopperEffects {

    public static void modeChanged(Player player) {
        if (!ChunkHopperPlugin.getInstance().getConfig().getBoolean("effects.enabled", true)) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
    }

    public static void filterAdded(Player player) {
        if (!ChunkHopperPlugin.getInstance().getConfig().getBoolean("effects.enabled", true)) return;
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.4f);
    }

    public static void filterRemoved(Player player) {
        if (!ChunkHopperPlugin.getInstance().getConfig().getBoolean("effects.enabled", true)) return;
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 0.7f);
    }

    public static void statsViewed(Player player) {
        if (!ChunkHopperPlugin.getInstance().getConfig().getBoolean("effects.enabled", true)) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.6f);
    }

    public static void itemCollected(Location loc) {
        if (!ChunkHopperPlugin.getInstance().getConfig().getBoolean("effects.enabled", true)) return;
        Location center = loc.clone().add(0.5, 0.7, 0.5);
        loc.getWorld().spawnParticle(Particle.SMOKE, center, 5, 0.1, 0.1, 0.1, 0.05);
    }
}
