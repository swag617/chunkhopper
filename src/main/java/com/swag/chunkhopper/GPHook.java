package com.swag.chunkhopper;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class GPHook {

    public boolean hasContainerTrust(Player player, Location location) {
        try {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
            if (claim == null) return false;
            return claim.checkPermission(player, ClaimPermission.Inventory, null) == null;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ChunkHopper] GPHook.hasContainerTrust threw an exception: " + e.getMessage());
            return false;
        }
    }

    public boolean hasBuildTrust(Player player, Location location) {
        try {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
            if (claim == null) return false;
            return claim.checkPermission(player, ClaimPermission.Build, null) == null;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ChunkHopper] GPHook.hasBuildTrust threw an exception: " + e.getMessage());
            return false;
        }
    }
}
