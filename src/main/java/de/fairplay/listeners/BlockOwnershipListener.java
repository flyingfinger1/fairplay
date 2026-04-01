package de.fairplay.listeners;

import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlockOwnershipListener implements Listener {

    private static final Set<Material> CROP_MATERIALS = Set.of(
        Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
        Material.NETHER_WART, Material.MELON_STEM, Material.PUMPKIN_STEM
    );

    private final OwnershipStorage storage;
    private final AdvancementManager adv;
    private final boolean teamMode;

    /** Tracks ownership of in-flight FallingBlock entities (entityUUID → ownerUUID). */
    private final Map<UUID, UUID> fallingOwners = new HashMap<>();

    public BlockOwnershipListener(OwnershipStorage storage, AdvancementManager adv, boolean teamMode) {
        this.storage = storage;
        this.adv = adv;
        this.teamMode = teamMode;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlock();
        UUID playerUUID = event.getPlayer().getUniqueId();
        storage.setBlockOwner(placed, playerUUID);

        BlockData data = placed.getBlockData();

        // Bed: also register the head part
        if (data instanceof Bed bed && bed.getPart() == Bed.Part.FOOT) {
            storage.setBlockOwner(placed.getRelative(bed.getFacing()), playerUUID);

        // Doors & tall plants (Bisected): also register the upper half
        } else if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.BOTTOM) {
            storage.setBlockOwner(placed.getRelative(BlockFace.UP), playerUUID);
        }

        // Chef: furnace types
        if (placed.getType() == Material.FURNACE
                || placed.getType() == Material.BLAST_FURNACE
                || placed.getType() == Material.SMOKER) {
            adv.grant(event.getPlayer(), "chef");
        }

        // Sowing: crop blocks
        if (placed.getBlockData() instanceof Ageable && isCropMaterial(placed.getType())) {
            adv.grant(event.getPlayer(), "sowing");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        // Creative players may break anything
        if (player.getGameMode() == GameMode.CREATIVE) {
            storage.removeBlockOwner(block);
            BlockData creativeData = block.getBlockData();
            if (creativeData instanceof Bed creativeBed) {
                BlockFace other = creativeBed.getPart() == Bed.Part.FOOT
                        ? creativeBed.getFacing()
                        : creativeBed.getFacing().getOppositeFace();
                storage.removeBlockOwner(block.getRelative(other));
            } else if (creativeData instanceof Bisected creativeBisected) {
                BlockFace other = creativeBisected.getHalf() == Bisected.Half.BOTTOM
                        ? BlockFace.UP : BlockFace.DOWN;
                storage.removeBlockOwner(block.getRelative(other));
            }
            return;
        }

        UUID owner = storage.getBlockOwner(block);

        // Bamboo fallback: grown blocks with no owner entry → walk down the stalk to find one
        if (owner == null && (block.getType() == Material.BAMBOO
                || block.getType() == Material.BAMBOO_SAPLING)) {
            Block check = block.getRelative(BlockFace.DOWN);
            while (owner == null && (check.getType() == Material.BAMBOO
                    || check.getType() == Material.BAMBOO_SAPLING)) {
                owner = storage.getBlockOwner(check);
                check = check.getRelative(BlockFace.DOWN);
            }
        }

        if (!teamMode && (owner == null || !owner.equals(player.getUniqueId()))) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "trespassing");
            return;
        }

        adv.grant(player, "own_hands");

        // Ernte: fully grown crop
        if (block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() == ageable.getMaximumAge()) {
            adv.grant(player, "harvest");
        }

        storage.removeBlockOwner(block);

        // Two-block structures: also remove the other half from the DB
        BlockData data = block.getBlockData();
        if (data instanceof Bed bed) {
            BlockFace other = bed.getPart() == Bed.Part.FOOT
                    ? bed.getFacing()
                    : bed.getFacing().getOppositeFace();
            storage.removeBlockOwner(block.getRelative(other));

        } else if (data instanceof Bisected bisected) {
            BlockFace other = bisected.getHalf() == Bisected.Half.BOTTOM
                    ? BlockFace.UP
                    : BlockFace.DOWN;
            storage.removeBlockOwner(block.getRelative(other));
        }
    }

    /**
     * Registers ownership of the placed water/lava source block.
     * BlockPlaceEvent does not fire reliably for bucket placements, so ownership
     * is registered explicitly here when the bucket is emptied.
     * MONITOR priority: runs after all other listeners, only if not cancelled.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Material bucket = event.getBucket();
        if (bucket != Material.WATER_BUCKET && bucket != Material.LAVA_BUCKET) return;
        storage.setBlockOwner(event.getBlock(), event.getPlayer().getUniqueId());
    }

    private static final BlockFace[] WATER_CHECK_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };

    /**
     * Water and lava buckets may only be filled from the player's own sources.
     * Applies to both pure water/lava blocks and waterlogged blocks
     * (e.g. fence in water): checks whether an adjacent owned water source exists.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        Material filled = block.getType();

        if (teamMode) return;

        // Direct water or lava source block
        if (filled == Material.WATER || filled == Material.LAVA) {
            UUID owner = storage.getBlockOwner(block);
            if (owner == null || !owner.equals(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.bucket"));
            }
            return;
        }

        // Waterlogged block (e.g. fence, stair, slab in water):
        // Allowed if at least one adjacent owned water source exists.
        if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
            UUID playerId = player.getUniqueId();
            for (BlockFace face : WATER_CHECK_FACES) {
                Block neighbor = block.getRelative(face);
                if (neighbor.getType() == Material.WATER
                        && playerId.equals(storage.getBlockOwner(neighbor))) {
                    return; // own water adjacent → allowed
                }
            }
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.bucket"));
        }
    }

    /**
     * Glass bottles may only be filled from the player's own water source blocks.
     * The cauldron case (BOTTLE_FILL) is already handled in CauldronListener.
     * Off-hand events are ignored to prevent double-processing.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBottleFill(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        var item = event.getItem();
        if (item == null || item.getType() != Material.GLASS_BOTTLE) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.WATER) return;

        if (teamMode) return;

        Player player = event.getPlayer();
        UUID owner = storage.getBlockOwner(block);

        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.bucket"));
        }
    }

    /**
     * Sweet berry harvest: block right-clicking on another player's berry bush.
     * The bush stays, but the berries belong to the owner.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBerryPick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SWEET_BERRY_BUSH) return;

        // Bush with age < 2 has no berries → no item drop, no check needed
        if (!(block.getBlockData() instanceof Ageable ageable) || ageable.getAge() < 2) return;

        if (teamMode) return;

        Player player = event.getPlayer();
        UUID owner = storage.getBlockOwner(block);
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "trespassing");
        }
    }

    /**
     * Farmland trampling: EntityChangeBlockEvent fires when FARMLAND → DIRT.
     * Only players are checked; other entities (animals etc.) are ignored.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getBlock().getType() != Material.FARMLAND) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (teamMode) return;

        UUID owner = storage.getBlockOwner(event.getBlock());
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "trespassing");
        }
    }

    /**
     * Gravity block starts falling (sand, gravel, concrete powder, anvils, …).
     * By the time EntitySpawnEvent fires, the source block is already AIR – but the
     * DB entry is still there (we only remove it in onBlockBreak). Read the owner,
     * store it keyed by entity UUID, and clean up the now-stale DB entry.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) return;

        Block source = falling.getLocation().getBlock();
        UUID owner = storage.getBlockOwner(source);
        if (owner == null) return;

        fallingOwners.put(falling.getUniqueId(), owner);
        storage.removeBlockOwner(source); // source is now AIR – remove stale entry
    }

    /**
     * Gravity block lands and becomes a solid block again.
     * Transfer the stored ownership to the landing position.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) return;

        UUID owner = fallingOwners.remove(event.getEntity().getUniqueId());
        if (owner != null) {
            storage.setBlockOwner(event.getBlock(), owner);
        }
    }

    /**
     * Falling block is removed without landing (void, lava, /kill, …).
     * Clean up the map to avoid a memory leak.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallingBlockRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) return;
        fallingOwners.remove(event.getEntity().getUniqueId());
    }

    private boolean isCropMaterial(Material m) {
        return CROP_MATERIALS.contains(m);
    }
}
