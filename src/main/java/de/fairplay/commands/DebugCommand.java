package de.fairplay.commands;

import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /fpdebug} — OP-only command to inspect and edit FairPlay ownership data.
 *
 * <h2>Syntax</h2>
 * <pre>
 * /fpdebug block &lt;x&gt; &lt;y&gt; &lt;z&gt; [world]
 * /fpdebug block &lt;x&gt; &lt;y&gt; &lt;z&gt; [world] set &lt;owner|fedby&gt; &lt;player|uuid&gt;
 * /fpdebug block &lt;x&gt; &lt;y&gt; &lt;z&gt; [world] clear
 *
 * /fpdebug entity &lt;uuid&gt;
 * /fpdebug entity &lt;uuid&gt; set &lt;owner|fedby&gt; &lt;player|uuid&gt;
 * /fpdebug entity &lt;uuid&gt; clear
 * </pre>
 *
 * <p>The {@code world} argument is optional when issued by a player (defaults to their
 * current world). The console must supply it explicitly.
 */
public class DebugCommand implements CommandExecutor, TabCompleter {

    private final OwnershipStorage storage;

    /**
     * Constructs a new DebugCommand with the given storage backend.
     *
     * @param storage the ownership storage to query and mutate
     */
    public DebugCommand(OwnershipStorage storage) {
        this.storage = storage;
    }

    // ── CommandExecutor ───────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "block"  -> handleBlock(sender, args);
            case "entity" -> handleEntity(sender, args);
            default       -> sendHelp(sender);
        }
        return true;
    }

    // ── block subcommand ──────────────────────────────────────────────────────

    private void handleBlock(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fpdebug block <x> <y> <z> [world] [set <owner|fedby> <player> | clear]");
            return;
        }

        int x, y, z;
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cCoordinates must be integers.");
            return;
        }

        // args[4] (if present) is either a world name, "set", or "clear"
        String worldName;
        int verbIdx;
        if (args.length >= 5
                && !args[4].equalsIgnoreCase("set")
                && !args[4].equalsIgnoreCase("clear")) {
            World w = Bukkit.getWorld(args[4]);
            if (w == null) {
                sender.sendMessage("§cUnknown world: §e" + args[4]);
                return;
            }
            worldName = w.getName();
            verbIdx = 5;
        } else if (sender instanceof Player p) {
            worldName = p.getWorld().getName();
            verbIdx = 4;
        } else {
            worldName = Bukkit.getWorlds().get(0).getName();
            verbIdx = 4;
        }

        if (args.length <= verbIdx) {
            showBlock(sender, worldName, x, y, z);
            return;
        }

        switch (args[verbIdx].toLowerCase()) {
            case "set" -> {
                if (args.length < verbIdx + 3) {
                    sender.sendMessage("§cUsage: set <owner|fedby> <player|uuid>");
                    return;
                }
                UUID target = resolvePlayer(args[verbIdx + 2]);
                if (target == null) {
                    sender.sendMessage("§cUnknown player or invalid UUID: §e" + args[verbIdx + 2]);
                    return;
                }
                setBlockField(sender, worldName, x, y, z, args[verbIdx + 1].toLowerCase(), target);
            }
            case "clear" -> {
                storage.removeBlockOwner(worldName, x, y, z);
                storage.removeBlockFedBy(worldName, x, y, z);
                sender.sendMessage("§aCleared block_ownership and block_fedby for "
                        + x + "," + y + "," + z + " in §e" + worldName);
            }
            default -> sender.sendMessage("§cUnknown verb: §e" + args[verbIdx]
                    + "§c. Expected §eset§c or §eclear§c.");
        }
    }

    private void showBlock(CommandSender sender, String world, int x, int y, int z) {
        UUID owner = storage.getBlockOwner(world, x, y, z);
        UUID fedBy = storage.getBlockFedBy(world, x, y, z);

        World w = Bukkit.getWorld(world);
        String typeStr = w != null ? " §8[" + w.getBlockAt(x, y, z).getType() + "]" : "";

        sender.sendMessage("§6§lFairPlay Block§r §7" + x + "," + y + "," + z
                + " (" + world + ")" + typeStr);
        sender.sendMessage("  §eblock_ownership: " + format(owner));
        sender.sendMessage("  §eblock_fedby:     " + format(fedBy));
    }

    private void setBlockField(CommandSender sender, String world,
                               int x, int y, int z, String field, UUID target) {
        switch (field) {
            case "owner" -> {
                storage.setBlockOwner(world, x, y, z, target);
                sender.sendMessage("§aSet block_ownership for "
                        + x + "," + y + "," + z + " → " + format(target));
            }
            case "fedby" -> {
                storage.setBlockFedBy(world, x, y, z, target);
                sender.sendMessage("§aSet block_fedby for "
                        + x + "," + y + "," + z + " → " + format(target));
            }
            default -> sender.sendMessage("§cUnknown field: §e" + field
                    + "§c. Use §eowner§c or §efedby§c.");
        }
    }

    // ── entity subcommand ─────────────────────────────────────────────────────

    private void handleEntity(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fpdebug entity <uuid> [set <owner|fedby> <player> | clear]");
            return;
        }

        UUID entityUUID;
        try {
            entityUUID = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid UUID: §e" + args[1]);
            return;
        }

        if (args.length == 2) {
            showEntity(sender, entityUUID);
            return;
        }

        switch (args[2].toLowerCase()) {
            case "set" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: set <owner|fedby> <player|uuid>");
                    return;
                }
                UUID target = resolvePlayer(args[4]);
                if (target == null) {
                    sender.sendMessage("§cUnknown player or invalid UUID: §e" + args[4]);
                    return;
                }
                setEntityField(sender, entityUUID, args[3].toLowerCase(), target);
            }
            case "clear" -> {
                storage.removeEntityOwner(entityUUID);
                storage.removeEntityFedBy(entityUUID);
                sender.sendMessage("§aCleared entity_ownership and entity_fedby for §e" + entityUUID);
            }
            default -> sender.sendMessage("§cUnknown verb: §e" + args[2]
                    + "§c. Expected §eset§c or §eclear§c.");
        }
    }

    private void showEntity(CommandSender sender, UUID entityUUID) {
        UUID owner = storage.getEntityOwner(entityUUID);
        UUID fedBy = storage.getEntityFedBy(entityUUID);

        sender.sendMessage("§6§lFairPlay Entity§r §7" + entityUUID);
        sender.sendMessage("  §eentity_ownership: " + format(owner));
        sender.sendMessage("  §eentity_fedby:     " + format(fedBy));
    }

    private void setEntityField(CommandSender sender, UUID entityUUID, String field, UUID target) {
        switch (field) {
            case "owner" -> {
                storage.setEntityOwner(entityUUID, target);
                sender.sendMessage("§aSet entity_ownership for §e" + entityUUID
                        + "§a → " + format(target));
            }
            case "fedby" -> {
                storage.setEntityFedBy(entityUUID, target);
                sender.sendMessage("§aSet entity_fedby for §e" + entityUUID
                        + "§a → " + format(target));
            }
            default -> sender.sendMessage("§cUnknown field: §e" + field
                    + "§c. Use §eowner§c or §efedby§c.");
        }
    }

    // ── TabCompleter ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        if (!sender.isOp()) return List.of();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(List.of("block", "entity"));

        } else if (args[0].equalsIgnoreCase("block")) {
            // /fpdebug block <x> <y> <z> [world|set|clear]
            if (args.length == 5) {
                Bukkit.getWorlds().forEach(w -> completions.add(w.getName()));
                completions.addAll(List.of("set", "clear"));
            } else if (args.length == 6) {
                completions.addAll(List.of("set", "clear"));
            } else if (args.length == 7) {
                completions.addAll(List.of("owner", "fedby"));
            } else if (args.length == 8) {
                Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
            }

        } else if (args[0].equalsIgnoreCase("entity")) {
            // /fpdebug entity <uuid> [set|clear]
            if (args.length == 3) {
                completions.addAll(List.of("set", "clear"));
            } else if (args.length == 4) {
                completions.addAll(List.of("owner", "fedby"));
            } else if (args.length == 5) {
                Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
            }
        }

        String prefix = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves a player name or UUID string to a UUID.
     * Checks online players first, then offline player records.
     * Returns {@code null} if the input cannot be resolved.
     */
    private UUID resolvePlayer(String nameOrUUID) {
        try { return UUID.fromString(nameOrUUID); } catch (IllegalArgumentException ignored) {}

        Player online = Bukkit.getPlayerExact(nameOrUUID);
        if (online != null) return online.getUniqueId();

        @SuppressWarnings("deprecation")
        var offline = Bukkit.getOfflinePlayer(nameOrUUID);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    /**
     * Formats a UUID for display: resolves player name if available.
     * Returns {@code §7null} for {@code null} input.
     */
    private String format(UUID uuid) {
        if (uuid == null) return "§7null";
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null
                ? "§f" + name + " §7(" + uuid + ")"
                : "§f" + uuid;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§lFairPlay Debug");
        sender.sendMessage("§e/fpdebug block §7<x> <y> <z> [world]");
        sender.sendMessage("§e/fpdebug block §7<x> <y> <z> [world] §eset §7<owner|fedby> <player>");
        sender.sendMessage("§e/fpdebug block §7<x> <y> <z> [world] §eclear");
        sender.sendMessage("§e/fpdebug entity §7<uuid>");
        sender.sendMessage("§e/fpdebug entity §7<uuid> §eset §7<owner|fedby> <player>");
        sender.sendMessage("§e/fpdebug entity §7<uuid> §eclear");
    }
}
