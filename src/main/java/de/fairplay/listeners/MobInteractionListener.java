package de.fairplay.listeners;

import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Tracks ownership of bred and tamed animals and restricts
 * mob interactions (shearing, brushing, milking, …) to the owner.
 */
public class MobInteractionListener implements Listener {

    private final OwnershipStorage storage;
    private final AdvancementManager adv;
    private final boolean teamMode;

    public MobInteractionListener(OwnershipStorage storage, AdvancementManager adv, boolean teamMode) {
        this.storage = storage;
        this.adv = adv;
        this.teamMode = teamMode;
    }

    // ── Ownership assignment ──────────────────────────────────────────────────

    /**
     * When two animals breed, the offspring belongs to the player who initiated it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;
        storage.setEntityOwner(event.getEntity().getUniqueId(), player.getUniqueId());
    }

    /**
     * When a player tames an animal (wolf, cat, horse, …) it belongs to them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) return;
        storage.setEntityOwner(event.getEntity().getUniqueId(), player.getUniqueId());
    }

    /**
     * Clean up the DB entry when an animal dies.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        storage.removeEntityOwner(event.getEntity().getUniqueId());
    }

    // ── Interaction restrictions ──────────────────────────────────────────────

    /**
     * Shearing sheep, snow golems, mushroom cows:
     * only allowed if the entity is unowned OR owned by the player.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (teamMode) return;
        if (!checkOwnership(event.getPlayer(), event.getEntity())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Lang.get(event.getPlayer(), "msg.mob_interact"));
        }
    }

    /**
     * PlayerInteractEntityEvent covers:
     *  - Brushing armadillos (BRUSH in hand)
     *  - Milking cows / mooshrooms (BUCKET in hand)
     *  - Collecting mushroom stew from mooshrooms (BOWL in hand)
     *
     * Only the main hand is checked to avoid double-firing.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (teamMode) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Material held = item.getType();

        // Armadillo brushing
        if (entity instanceof Armadillo && held == Material.BRUSH) {
            if (!checkOwnership(player, entity)) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.mob_interact"));
            }
            return;
        }

        // Milking cows and mooshrooms
        if ((entity instanceof Cow || entity instanceof MushroomCow) && held == Material.BUCKET) {
            if (!checkOwnership(player, entity)) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.mob_interact"));
            }
            return;
        }

        // Collecting mushroom stew from mooshrooms
        if (entity instanceof MushroomCow && held == Material.BOWL) {
            if (!checkOwnership(player, entity)) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.mob_interact"));
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Returns true only if the entity is owned by this specific player.
     * Wild/unowned animals cannot be interacted with.
     */
    private boolean checkOwnership(Player player, Entity entity) {
        UUID owner = storage.getEntityOwner(entity.getUniqueId());
        return owner != null && owner.equals(player.getUniqueId());
    }
}
