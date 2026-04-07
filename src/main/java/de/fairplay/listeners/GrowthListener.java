package de.fairplay.listeners;

import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.Set;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Propagates block ownership to newly grown or spread blocks.
 *
 * <p>Covered growth types:
 * <ul>
 *   <li><b>Trees / mushrooms / bamboo</b> — {@link org.bukkit.event.world.StructureGrowEvent}</li>
 *   <li><b>Bone meal</b> — {@link org.bukkit.event.block.BlockFertilizeEvent}; also blocks
 *       fertilising foreign blocks</li>
 *   <li><b>Upward/downward growth</b> (sugar cane, cactus, vines, kelp) —
 *       {@link org.bukkit.event.block.BlockGrowEvent}</li>
 *   <li><b>Spreading organisms</b> (sculk, vines, moss, berry bushes) —
 *       {@link org.bukkit.event.block.BlockSpreadEvent}</li>
 *   <li><b>Block formation</b> (cobblestone/stone generators) —
 *       {@link org.bukkit.event.block.BlockFormEvent}</li>
 *   <li><b>Dripstone growth</b> — {@link org.bukkit.event.block.BlockPhysicsEvent}
 *       (BlockGrowEvent/BlockFormEvent do not fire for dripstone in Paper 1.21.8)</li>
 * </ul>
 */
public class GrowthListener implements Listener {

    private static final BlockFace[] ALL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };


    private final OwnershipStorage storage;
    private final JavaPlugin plugin;
    private final AdvancementManager adv;
    private final boolean teamMode;

    public GrowthListener(JavaPlugin plugin, OwnershipStorage storage, AdvancementManager adv, boolean teamMode) {
        this.plugin = plugin;
        this.storage = storage;
        this.adv = adv;
        this.teamMode = teamMode;
    }

    /**
     * Tree, mushroom, bamboo etc. grows from a placed sapling/spore.
     * All newly generated blocks receive the ownership of the sapling.
     */
    @EventHandler
    public void onStructureGrow(StructureGrowEvent event) {
        UUID owner = storage.getBlockOwner(event.getLocation().getBlock());
        if (owner == null) return;

        String world = event.getWorld().getName();
        for (BlockState state : event.getBlocks()) {
            storage.setBlockOwner(world, state.getX(), state.getY(), state.getZ(), owner);
        }

        Player player = plugin.getServer().getPlayer(owner);
        if (player != null) adv.grant(player, "my_forest");
    }

    /**
     * Bone meal on a block:
     *  - Not owned block → action is blocked
     *  - Owned block → all generated blocks are assigned to the player
     * Applies to moss, pale moss, grass, crops, lily pad etc.
     */
    @EventHandler
    public void onBlockFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null) return; // Dispenser – no ownership check

        if (teamMode) return;

        UUID owner = storage.getBlockOwner(event.getBlock());
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.fertilize"));
            return;
        }

        String world = event.getBlock().getWorld().getName();
        for (BlockState state : event.getBlocks()) {
            storage.setBlockOwner(world, state.getX(), state.getY(), state.getZ(), owner);
        }
    }

    // Plants where BlockGrowEvent fires on the existing tip block,
    // but the new block only appears one tick later.
    private static final Set<Material> DELAYED_GROW_PLANTS = Set.of(
        Material.BAMBOO, Material.BAMBOO_SAPLING,
        Material.KELP,   Material.KELP_PLANT
    );

    /**
     * A block grows upward or downward:
     *  - Upward (sugar cane, cactus, twisting vines): ownership from the block below
     *  - Downward (cave vines, weeping vines): ownership from the block above
     * Crops/potatoes grow at the same coordinate – DB entry persists automatically.
     *
     * Bamboo/kelp special case: BlockGrowEvent fires on the existing tip (age change)
     * before the new block appears above.
     * Fix: wait 1 tick, then register the block above.
     */
    @EventHandler
    public void onBlockGrow(BlockGrowEvent event) {
        Block grownBlock = event.getBlock();

        // New block appears above (sugar cane, cactus, …) – only if not already owned.
        UUID owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.DOWN));
        if (owner != null && storage.getBlockOwner(grownBlock) == null) {
            storage.setBlockOwner(grownBlock, owner);
            return;
        }

        // New block appears below (cave vines, weeping vines) – only if not already owned.
        owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.UP));
        if (owner != null && storage.getBlockOwner(grownBlock) == null) {
            storage.setBlockOwner(grownBlock, owner);
            return;
        }

        Material newType = event.getNewState().getType();

        // Case A – stem-side event: stem changes to ATTACHED_*_STEM.
        // Its Directional BlockData tells us exactly which block the fruit grew into.
        if (newType == Material.ATTACHED_MELON_STEM || newType == Material.ATTACHED_PUMPKIN_STEM) {
            owner = storage.getBlockOwner(grownBlock); // grownBlock = stem position
            if (owner != null && event.getNewState().getBlockData() instanceof Directional dir) {
                storage.setBlockOwner(grownBlock.getRelative(dir.getFacing()), owner);
            }
            return;
        }

        // Dripstone growth is handled in onBlockForm (BlockGrowEvent does not fire in Paper 1.21.8).

        // Bamboo / Kelp: event fires on existing tip → new block appears after the event.
        // Wait 1 tick, then register the block above.
        if (DELAYED_GROW_PLANTS.contains(grownBlock.getType())) {
            UUID tipOwner = storage.getBlockOwner(grownBlock);
            if (tipOwner != null) {
                final UUID finalOwner = tipOwner;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    Block above = grownBlock.getRelative(BlockFace.UP);
                    if (DELAYED_GROW_PLANTS.contains(above.getType())
                            && storage.getBlockOwner(above) == null) {
                        storage.setBlockOwner(above, finalOwner);
                    }
                }, 1L);
            }
        }
    }

    /**
     * Spread of organisms to adjacent blocks.
     * Ownership is only transferred for plants/organisms where it makes sense.
     * Grass/mycelium are intentionally excluded (the player already owns the target
     * dirt block if they placed it).
     */
    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        if (!transfersOwnership(event.getSource().getType())) return;
        // Never overwrite a block that already has an owner.
        if (storage.getBlockOwner(event.getBlock()) != null) return;

        UUID owner = storage.getBlockOwner(event.getSource());
        if (owner != null) {
            storage.setBlockOwner(event.getBlock(), owner);
        }
    }

    /**
     * Cobblestone/obsidian/stone generator: at least one of the involved
     * sources (lava or water) must have been placed by the player.
     */
    @EventHandler
    public void onBlockForm(BlockFormEvent event) {
        Block formed = event.getBlock();
        String world = formed.getWorld().getName();

        // Dripstone is handled via BlockPhysicsEvent (BlockGrowEvent/BlockFormEvent do not
        // fire for pointed dripstone growth in Paper 1.21.8).

        for (BlockFace face : ALL_FACES) {
            Block neighbor = formed.getRelative(face);
            UUID owner = storage.getBlockOwner(world, neighbor.getX(), neighbor.getY(), neighbor.getZ());
            if (owner != null) {
                // Never overwrite an already-owned block (e.g. a waterlogged placed block).
                if (storage.getBlockOwner(formed) == null) {
                    storage.setBlockOwner(formed, owner);
                } else if (!owner.equals(storage.getBlockOwner(formed))) {
                    plugin.getLogger().warning("[FairPlay] BlockFormEvent tried to overwrite owner"
                        + " at " + formed.getType() + " " + formed.getX() + "," + formed.getY() + "," + formed.getZ()
                        + " newState=" + event.getNewState().getType()
                        + " – keeping existing owner. (source neighbour: " + neighbor.getType() + ")");
                }

                // Stein ohne Ende: cobblestone/stone forms from lava+water
                if (event.getNewState().getType() == Material.COBBLESTONE
                        || event.getNewState().getType() == Material.STONE
                        || event.getNewState().getType() == Material.COBBLED_DEEPSLATE) {
                    Player player = plugin.getServer().getPlayer(owner);
                    if (player != null) adv.grant(player, "endless_stone");
                }

                return;
            }
        }
    }

    /**
     * BlockGrowEvent and BlockFormEvent do not fire for dripstone growth in Paper 1.21.8.
     * BlockPhysicsEvent fires on every block update – including when a new dripstone tip
     * appears. We filter aggressively (type + already-owned check) so the cost is minimal.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDripstonePhysics(BlockPhysicsEvent event) {
        if (event.getBlock().getType() != Material.POINTED_DRIPSTONE) return;
        if (storage.getBlockOwner(event.getBlock()) != null) return; // already owned – skip
        assignDripstoneOwnership(event.getBlock());
    }

    /**
     * Assigns ownership to a newly grown POINTED_DRIPSTONE tip by scanning the
     * dripstone column up (stalactite growing down / new stalagmite from drip)
     * and down (stalagmite growing up) for the nearest owned piece.
     */
    private void assignDripstoneOwnership(Block tip) {
        if (storage.getBlockOwner(tip) != null) return;

        // Scan upward – covers stalactites growing down and the new stalagmite case
        Block scan = tip.getRelative(BlockFace.UP);
        for (int i = 0; i < 24; i++) {
            Material t = scan.getType();
            if (t == Material.POINTED_DRIPSTONE || t == Material.DRIPSTONE_BLOCK) {
                UUID owner = storage.getBlockOwner(scan);
                if (owner != null) { storage.setBlockOwner(tip, owner); return; }
            } else if (t != Material.AIR && t != Material.CAVE_AIR) {
                break;
            }
            scan = scan.getRelative(BlockFace.UP);
        }

        // Scan downward – covers stalagmites growing upward
        scan = tip.getRelative(BlockFace.DOWN);
        for (int i = 0; i < 24; i++) {
            Material t = scan.getType();
            if (t == Material.POINTED_DRIPSTONE || t == Material.DRIPSTONE_BLOCK) {
                UUID owner = storage.getBlockOwner(scan);
                if (owner != null) { storage.setBlockOwner(tip, owner); return; }
            } else {
                break;
            }
            scan = scan.getRelative(BlockFace.DOWN);
        }
    }

    /**
     * Materials whose spread transfers ownership.
     */
    private boolean transfersOwnership(Material material) {
        return switch (material) {
            case PUMPKIN_STEM, ATTACHED_PUMPKIN_STEM,
                 MELON_STEM, ATTACHED_MELON_STEM,
                 // Sculk
                 SCULK, SCULK_CATALYST, SCULK_SENSOR, SCULK_SHRIEKER, SCULK_VEIN,
                 // Vines
                 VINE,
                 CAVE_VINES, CAVE_VINES_PLANT,
                 WEEPING_VINES, WEEPING_VINES_PLANT,
                 TWISTING_VINES, TWISTING_VINES_PLANT,
                 // Moss
                 MOSS_BLOCK, PALE_MOSS_BLOCK,
                 // Berry bushes
                 SWEET_BERRY_BUSH,
                 // Kelp
                 KELP, KELP_PLANT -> true;
            default -> false;
        };
    }
}
