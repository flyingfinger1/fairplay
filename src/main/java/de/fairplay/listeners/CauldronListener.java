package de.fairplay.listeners;

import de.fairplay.FairPlayPlugin;
import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;

import java.util.UUID;

public class CauldronListener implements Listener {

    // Vanilla-Maximum für Dripstone-Reichweite
    private static final int MAX_DRIPSTONE_SEARCH = 12;

    private final OwnershipStorage storage;
    private final FairPlayPlugin plugin;
    private final AdvancementManager adv;

    public CauldronListener(OwnershipStorage storage, FairPlayPlugin plugin, AdvancementManager adv) {
        this.storage = storage;
        this.plugin = plugin;
        this.adv = adv;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCauldronLevelChange(CauldronLevelChangeEvent event) {
        switch (event.getReason()) {
            case NATURAL_FILL  -> handleDripstoneFill(event);
            case BOTTLE_FILL,
                 BUCKET_FILL   -> handleTakeFromCauldron(event);
        }
    }

    /**
     * Dripstone tropft in den Kessel: den Tropfstein selbst suchen.
     * Hat der Spieler den Tropfstein platziert, bekommt der Kessel seine Ownership –
     * unabhängig davon woher die Flüssigkeit über dem Tropfstein stammt.
     */
    private void handleDripstoneFill(CauldronLevelChangeEvent event) {
        Block cauldron = event.getBlock();
        Block search = cauldron.getRelative(BlockFace.UP);

        for (int i = 0; i < MAX_DRIPSTONE_SEARCH; i++) {
            Material type = search.getType();

            if (type == Material.POINTED_DRIPSTONE) {
                UUID owner = storage.getBlockOwner(search);
                if (owner != null) {
                    storage.setBlockOwner(cauldron, owner);
                    Player player = plugin.getServer().getPlayer(owner);
                    if (player != null) adv.grant(player, "tropfstein_trick");
                }
                return;
            }

            // Weiter nach oben falls es Luft oder Höhlenluft ist
            if (type != Material.AIR && type != Material.CAVE_AIR) {
                return; // Irgendetwas anderes blockiert den Pfad
            }

            search = search.getRelative(BlockFace.UP);
        }
    }

    /**
     * Spieler entnimmt Wasser (Flasche) oder Lava (Eimer) aus dem Kessel.
     * Nur erlaubt wenn der Kessel dem Spieler gehört.
     * Wird der Kessel vollständig geleert, wird der Ownership-Eintrag entfernt.
     */
    private void handleTakeFromCauldron(CauldronLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID owner = storage.getBlockOwner(event.getBlock());
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.cauldron"));
            return;
        }

        if (event.getNewState().getType() == Material.CAULDRON) {
            storage.removeBlockOwner(event.getBlock());
        }
    }
}
