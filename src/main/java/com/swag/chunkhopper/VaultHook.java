package com.swag.chunkhopper;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;

public class VaultHook {

    private final Economy economy;

    public VaultHook(Economy economy) {
        this.economy = economy;
    }

    public boolean has(Player p, double amount) {
        return economy.has(p, amount);
    }

    public boolean withdraw(Player p, double amount) {
        return economy.withdrawPlayer(p, amount).transactionSuccess();
    }

    public String format(double amount) {
        return economy.format(amount);
    }
}
