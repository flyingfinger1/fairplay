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

    // Vanilla maximum search depth for dripstone
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
     * Dripstone drips into the cauldron: locate the dripstone block.
     * If the player placed the dripstone, the cauldron receives their ownership –
     * regardless of where the liquid above the dripstone comes from.
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

            // Continue upward if air or cave air
            if (type != Material.AIR && type != Material.CAVE_AIR) {
                return; // Something else is blocking the path
            }

            search = search.getRelative(BlockFace.UP);
        }
    }

    /**
     * Player draws water (bottle) or lava (bucket) from the cauldron.
     * Only allowed if the player owns the cauldron.
     * If the cauldron is fully emptied, the ownership entry is removed.
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
