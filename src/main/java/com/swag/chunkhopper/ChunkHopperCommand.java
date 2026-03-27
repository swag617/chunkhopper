package com.swag.chunkhopper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ChunkHopperCommand implements CommandExecutor, TabCompleter {

    private final ChunkHopperPlugin plugin;

    public ChunkHopperCommand(ChunkHopperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("\u00a7e[ChunkHopper] Use /chunkhopper help for commands.");
            return true;
        }

        String sub = args[0].toLowerCase();

        // -------------------------------------------------------
        // give
        // -------------------------------------------------------
        if (sub.equals("give")) {
            if (!sender.hasPermission("chunkhopper.admin")) {
                sender.sendMessage("\u00a7cNo permission.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("\u00a7cUsage: /chunkhopper give <player|%player_name%> [amount]");
                return true;
            }

            String targetName = args[1];
            String resolvedName = targetName;

            // PlaceholderAPI support
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Player senderPlayer = (sender instanceof Player) ? (Player) sender : null;
                try {
                    Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                    java.lang.reflect.Method setPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
                    Object result = setPlaceholders.invoke(null, senderPlayer, targetName);
                    if (result instanceof String) resolvedName = (String) result;
                } catch (Throwable ignored) {}
            }

            // Handle %player_name% resolving to self
            if (resolvedName != null && resolvedName.equalsIgnoreCase(targetName)
                    && targetName.equalsIgnoreCase("%player_name%") && sender instanceof Player) {
                resolvedName = sender.getName();
            }

            Player target = Bukkit.getPlayerExact(resolvedName);
            if (target == null) {
                sender.sendMessage("\u00a7cPlayer not found: " + resolvedName);
                return true;
            }

            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }
            amount = Math.max(1, Math.min(64, amount));

            // Use central factory — no inline item building
            ItemStack hopper = plugin.getManager().buildHopperItem(amount);

            target.getInventory().addItem(hopper).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));

            sender.sendMessage("\u00a7aGave " + amount + " Chunk Hopper(s) to " + target.getName() + ".");
            return true;
        }

        // -------------------------------------------------------
        // reload
        // -------------------------------------------------------
        if (sub.equals("reload")) {
            if (!sender.hasPermission("chunkhopper.admin")) {
                sender.sendMessage("\u00a7cNo permission.");
                return true;
            }
            plugin.reloadConfig();
            if (plugin.getManager() != null) {
                plugin.getManager().loadConfig();
            }
            sender.sendMessage("\u00a7aConfiguration reloaded.");
            return true;
        }

        // -------------------------------------------------------
        // help (default)
        // -------------------------------------------------------
        sender.sendMessage("\u00a7e\u00a7l--- ChunkHopper Help ---");
        sender.sendMessage("\u00a76/ch give <player|%player_name%> [amount] \u00a77- Give hoppers");
        sender.sendMessage("\u00a76/ch reload \u00a77- Reload config");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.addAll(Arrays.asList("give", "reload", "help"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            suggestions.add("%player_name%");
            suggestions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            suggestions.addAll(Arrays.asList("1", "16", "64"));
        }

        if (args.length == 0) return suggestions;
        String typed = args[args.length - 1].toLowerCase();
        return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(typed))
                .sorted()
                .collect(Collectors.toList());
    }
}
