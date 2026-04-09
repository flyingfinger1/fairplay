package de.fairplay.advancements;

import de.fairplay.FairPlayPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Manages the FairPlay data pack and custom advancements.
 *
 * <p>On every plugin start, {@link #install()} copies the embedded data pack files
 * (advancements, {@code pack.mcmeta}) to the primary world's {@code datapacks/fairplay/}
 * directory and triggers a server data reload if anything changed.
 * Obsolete advancement files left over from older versions are deleted automatically.
 */
public class AdvancementManager {

    private static final String NAMESPACE = "fairplay";
    private static final List<String> FILES = List.of(
        "pack.mcmeta",
        "data/fairplay/advancement/root.json",
        "data/fairplay/advancement/trespassing.json",
        "data/fairplay/advancement/own_hands.json",
        "data/fairplay/advancement/endless_stone.json",
        "data/fairplay/advancement/collector.json",
        "data/fairplay/advancement/foundation.json",
        "data/fairplay/advancement/chef.json",
        "data/fairplay/advancement/endless_iron.json",
        "data/fairplay/advancement/empty_hands.json",
        "data/fairplay/advancement/diy.json",
        "data/fairplay/advancement/green_thumb.json",
        "data/fairplay/advancement/my_forest.json",
        "data/fairplay/advancement/sowing.json",
        "data/fairplay/advancement/harvest.json",
        "data/fairplay/advancement/dripstone_trick.json",
        "data/fairplay/advancement/water_management.json",
        "data/fairplay/advancement/infinite.json",
        "data/fairplay/advancement/hot_lava.json",
        "data/fairplay/advancement/no_violence.json",
        "data/fairplay/advancement/resourcefulness.json",
        "data/fairplay/advancement/mans_best_friend.json",
        "data/fairplay/advancement/bodyguard.json",
        "data/fairplay/advancement/own_fleet.json",
        "data/fairplay/advancement/good_night.json",
        "data/fairplay/advancement/bed_to_go.json",
        "data/fairplay/advancement/fair_trade.json",
        "data/fairplay/advancement/ignition.json",
        "data/fairplay/advancement/first_night.json"
    );

    private final FairPlayPlugin plugin;

    /**
     * Constructs a new AdvancementManager with the given plugin instance.
     *
     * @param plugin the owning {@link FairPlayPlugin}, used for server access and logging
     */
    public AdvancementManager(FairPlayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Installs the data pack and returns whether any files changed.
     * If files changed, the caller must trigger server.reloadData() so the new files
     * take effect in the same server session.
     *
     * @return {@code true} if any data pack files were added, updated, or removed;
     *         {@code false} if everything was already up to date or installation failed
     */
    public boolean install() {
        File worldFolder = plugin.getServer().getWorlds().get(0).getWorldFolder();
        File datapackDir = new File(worldFolder, "datapacks/" + NAMESPACE);
        boolean freshInstall = !datapackDir.exists();
        boolean anyChanged  = freshInstall;

        // Build a set of canonical paths that belong to the current version
        java.util.Set<File> knownFiles = new java.util.HashSet<>();
        for (String file : FILES) knownFiles.add(new File(datapackDir, file).getAbsoluteFile());

        try {
            // Copy / update all files that are part of the current version
            for (String file : FILES) {
                File target = new File(datapackDir, file);
                target.getParentFile().mkdirs();
                try (InputStream in = plugin.getResource("datapack/" + file)) {
                    if (in == null) continue;

                    byte[] newBytes = in.readAllBytes();

                    // Only overwrite if content has changed
                    if (target.exists() && Arrays.equals(newBytes, Files.readAllBytes(target.toPath()))) {
                        continue; // unchanged
                    }

                    Files.write(target.toPath(), newBytes, StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    anyChanged = true;
                }
            }

            // Remove advancement JSON files that are no longer part of this version
            // (e.g. old German-named files left over from a previous release).
            File advDir = new File(datapackDir, "data/" + NAMESPACE + "/advancement");
            if (advDir.isDirectory()) {
                File[] obsolete = advDir.listFiles(
                    f -> f.getName().endsWith(".json") && !knownFiles.contains(f.getAbsoluteFile()));
                if (obsolete != null) {
                    for (File f : obsolete) {
                        if (f.delete()) {
                            plugin.getLogger().info("Data pack: removed obsolete advancement " + f.getName());
                            anyChanged = true;
                        }
                    }
                }
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Data pack installation failed: " + e.getMessage());
            return false;
        }

        if (freshInstall) {
            plugin.getLogger().info("Data pack installed.");
        } else if (anyChanged) {
            plugin.getLogger().info("Data pack updated – reloading data…");
        }
        return anyChanged;
    }

    /**
     * Grants a FairPlay advancement to a player if they have not already earned it.
     *
     * @param player the player who should receive the advancement
     * @param key    the advancement key within the {@code fairplay} namespace (e.g. {@code "foundation"})
     */
    public void grant(Player player, String key) {
        Advancement adv = plugin.getServer().getAdvancement(new NamespacedKey(NAMESPACE, key));
        if (adv == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (!progress.isDone()) {
            progress.getRemainingCriteria().forEach(progress::awardCriteria);
        }
    }

    /**
     * Returns whether a player has already earned a FairPlay advancement.
     *
     * @param player the player to check
     * @param key    the advancement key within the {@code fairplay} namespace
     * @return {@code true} if the advancement is complete, {@code false} otherwise
     */
    public boolean has(Player player, String key) {
        Advancement adv = plugin.getServer().getAdvancement(new NamespacedKey(NAMESPACE, key));
        if (adv == null) return false;
        return player.getAdvancementProgress(adv).isDone();
    }
}
