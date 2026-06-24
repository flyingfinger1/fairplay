package de.fairplay.listeners;

import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Tracks ownership of bred and tamed animals and restricts
 * mob interactions (shearing, brushing, milking, …) to the owner.
 */
public class MobInteractionListener implements Listener {

    private final OwnershipStorage storage;
    private final AdvancementManager adv;
    private final boolean teamMode;
    private final JavaPlugin plugin;

    /**
     * Constructs a new MobInteractionListener with the given dependencies.
     *
     * @param storage  the ownership storage used to read and write entity owners
     * @param adv      the advancement manager used to grant advancements
     * @param teamMode {@code true} if team mode is active (ownership checks are relaxed)
     * @param plugin   the owning plugin, used for logging
     */
    public MobInteractionListener(OwnershipStorage storage, AdvancementManager adv, boolean teamMode, JavaPlugin plugin) {
        this.storage = storage;
        this.adv = adv;
        this.teamMode = teamMode;
        this.plugin = plugin;
    }

    // ── Ownership assignment ──────────────────────────────────────────────────

    /**
     * Tracks who fed a turtle or frog to initiate breeding.
     *
     * <p>Turtles and frogs do not produce a baby entity on breeding — they lay a block
     * instead (turtle egg / frogspawn). {@link EntityBreedEvent} therefore never fires
     * for them. We use {@link EntityEnterLoveModeEvent} to record the feeding player on
     * the entity's {@code entity_fedby} entry. {@link GrowthListener} reads this entry
     * when the entity later lays its eggs/frogspawn and assigns the block-level
     * {@code block_fedby} (and, if the entity is already owned, also {@code block_ownership}).
     *
     * <p>The entry persists on the entity until it is fed again (overwritten) or dies
     * (cleaned up by {@link #onEntityDeath}). A dispenser-fed entity (humanEntity == null)
     * gets no entry so its eggs / frogspawn remain unowned.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggLayerFed(EntityEnterLoveModeEvent event) {
        if (!(event.getEntity() instanceof Turtle) && !(event.getEntity() instanceof Frog)) return;
        if (event.getHumanEntity() == null) return; // fed by dispenser – no marker
        storage.setEntityFedBy(event.getEntity().getUniqueId(),
                event.getHumanEntity().getUniqueId());
    }

    /**
     * When a tadpole grows into a frog, promote its ownership to the new frog entity.
     * The tadpole entity is replaced (not killed), so {@link #onEntityDeath} does not
     * fire — we clean up the old entries here manually.
     *
     * <p>Two cases:
     * <ul>
     *   <li><b>Cycle 2 tadpole</b> (already had {@code entity_ownership}): ownership is
     *       transferred directly to the frog.</li>
     *   <li><b>Cycle 1 tadpole</b> (only had {@code entity_fedby}): the frog receives
     *       {@code entity_ownership} derived from that fed-by entry. This is the moment
     *       the player's investment in cycle 1 pays off — the frog is now owned and its
     *       future frogspawn will be cycle-2 (tadpoles immediately bucketable).</li>
     * </ul>
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTadpoleGrow(EntityTransformEvent event) {
        if (!(event.getEntity() instanceof Tadpole)) return;
        UUID tadpoleId = event.getEntity().getUniqueId();
        UUID frogId    = event.getTransformedEntity().getUniqueId();

        // Cycle 2: tadpole was directly owned (bucketable) → transfer to frog.
        UUID owner = storage.getEntityOwner(tadpoleId);
        if (owner != null) {
            storage.setEntityOwner(frogId, owner);
        } else {
            // Cycle 1: tadpole only had entity_fedby → frog becomes owned by that player.
            UUID fedBy = storage.getEntityFedBy(tadpoleId);
            if (fedBy != null) {
                storage.setEntityOwner(frogId, fedBy);
            }
        }
        // Tadpole transformation is not a death event — clean up manually.
        storage.removeEntityOwner(tadpoleId);
        storage.removeEntityFedBy(tadpoleId);
    }

    /**
     * When two animals breed, the offspring belongs to the player who initiated it.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;
        storage.setEntityOwner(event.getEntity().getUniqueId(), player.getUniqueId());
    }

    /**
     * When a player tames an animal (wolf, cat, horse, …) it belongs to them.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) return;
        storage.setEntityOwner(event.getEntity().getUniqueId(), player.getUniqueId());
    }

    /**
     * Clean up the DB entries when an animal dies.
     * Removes both the ownership entry and the "fed by" marker (used for turtles).
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        storage.removeEntityOwner(uuid);
        storage.removeEntityFedBy(uuid);
    }

    // ── Interaction restrictions ──────────────────────────────────────────────

    /**
     * Shearing sheep, snow golems, mushroom cows:
     * only allowed if the entity is unowned OR owned by the player.
     *
     * @param event the event fired by the server
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
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (teamMode) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Material held = item.getType();

        // Bucketing fish (Cod, Salmon, TropicalFish, PufferFish):
        // Fish cannot be bred, so they can never be entity-owned. Instead, a player may
        // only bucket a fish if it is swimming inside a water block they own.
        // This encourages building fish farms while keeping wild ocean fish untouchable.
        if ((entity instanceof Cod || entity instanceof Salmon
                || entity instanceof TropicalFish || entity instanceof PufferFish)
                && held == Material.WATER_BUCKET) {
            Block waterBlock = entity.getLocation().getBlock();
            UUID blockOwner = storage.getBlockOwner(waterBlock);
            if (blockOwner == null || !blockOwner.equals(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.mob_interact"));
            }
            return;
        }

        // Bucketing tadpoles (only cycle-2 tadpoles have entity_ownership and are bucketable)
        // Bucketing axolotls (owned via EntityBreedEvent, same check applies)
        if ((entity instanceof Tadpole || entity instanceof Axolotl) && held == Material.WATER_BUCKET) {
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
