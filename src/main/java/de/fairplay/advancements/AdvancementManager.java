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

public class AdvancementManager {

    private static final String NAMESPACE = "fairplay";
    private static final List<String> FILES = List.of(
        "pack.mcmeta",
        "data/fairplay/advancement/root.json",
        "data/fairplay/advancement/fremdes_eigentum.json",
        "data/fairplay/advancement/eigene_haende.json",
        "data/fairplay/advancement/stein_ohne_ende.json",
        "data/fairplay/advancement/sammler.json",
        "data/fairplay/advancement/grundstein.json",
        "data/fairplay/advancement/koch.json",
        "data/fairplay/advancement/eisen_ohne_ende.json",
        "data/fairplay/advancement/leere_haende.json",
        "data/fairplay/advancement/selbst_ist_der_mann.json",
        "data/fairplay/advancement/gruener_daumen.json",
        "data/fairplay/advancement/mein_wald.json",
        "data/fairplay/advancement/aussaat.json",
        "data/fairplay/advancement/ernte.json",
        "data/fairplay/advancement/tropfstein_trick.json",
        "data/fairplay/advancement/wasserwirtschaft.json",
        "data/fairplay/advancement/unendlich.json",
        "data/fairplay/advancement/heisse_lava.json",
        "data/fairplay/advancement/keine_gewalt.json",
        "data/fairplay/advancement/einfallsreichtum.json",
        "data/fairplay/advancement/des_menschen_bester_freund.json",
        "data/fairplay/advancement/bodyguard.json",
        "data/fairplay/advancement/eigene_flotte.json",
        "data/fairplay/advancement/gute_nacht.json",
        "data/fairplay/advancement/bett_to_go.json",
        "data/fairplay/advancement/fairer_handel.json",
        "data/fairplay/advancement/zuendung.json"
    );

    private final FairPlayPlugin plugin;

    public AdvancementManager(FairPlayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Installs the data pack and returns whether any files changed.
     * If files changed, the caller must trigger server.reloadData() so the new files
     * take effect in the same server session.
     */
    public boolean install() {
        File worldFolder = plugin.getServer().getWorlds().get(0).getWorldFolder();
        File datapackDir = new File(worldFolder, "datapacks/" + NAMESPACE);
        boolean freshInstall = !datapackDir.exists();
        boolean anyChanged  = freshInstall;

        try {
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

    public void grant(Player player, String key) {
        Advancement adv = plugin.getServer().getAdvancement(new NamespacedKey(NAMESPACE, key));
        if (adv == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (!progress.isDone()) {
            progress.getRemainingCriteria().forEach(progress::awardCriteria);
        }
    }

    public boolean has(Player player, String key) {
        Advancement adv = plugin.getServer().getAdvancement(new NamespacedKey(NAMESPACE, key));
        if (adv == null) return false;
        return player.getAdvancementProgress(adv).isDone();
    }
}
