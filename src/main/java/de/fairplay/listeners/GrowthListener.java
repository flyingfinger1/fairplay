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
import org.bukkit.entity.Frog;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tadpole;
import org.bukkit.entity.Turtle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
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

    /**
     * Constructs a new GrowthListener with the given dependencies.
     *
     * @param plugin   the owning plugin, used for scheduling delayed tasks
     * @param storage  the ownership storage used to read and write block owners
     * @param adv      the advancement manager used to grant advancements
     * @param teamMode {@code true} if team mode is active (ownership checks are relaxed)
     */
    public GrowthListener(JavaPlugin plugin, OwnershipStorage storage, AdvancementManager adv, boolean teamMode) {
        this.plugin = plugin;
        this.storage = storage;
        this.adv = adv;
        this.teamMode = teamMode;
    }

    /**
     * Tree, mushroom, bamboo etc. grows from a placed sapling/spore.
     * All newly generated blocks receive the ownership of the sapling.
     *
     * @param event the event fired by the server
     */
    @EventHandler
    public void onStructureGrow(StructureGrowEvent event) {
        UUID owner = storage.getBlockOwner(event.getLocation().getBlock());
        if (owner == null) return;

        storage.setBlockOwnerBatch(event.getWorld().getName(), event.getBlocks(), owner);

        Player player = plugin.getServer().getPlayer(owner);
        if (player != null) adv.grant(player, "my_forest");
    }

    /**
     * Bone meal on a block:
     *  - Not owned block → action is blocked
     *  - Owned block → all generated blocks are assigned to the player
     * Applies to moss, pale moss, grass, crops, lily pad etc.
     *
     * @param event the event fired by the server
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

        storage.setBlockOwnerBatch(event.getBlock().getWorld().getName(), event.getBlocks(), owner);
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
     *
     * @param event the event fired by the server
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

        // Case B – fruit-side event: the pumpkin/melon block itself appears.
        // BlockGrowEvent fires separately for the fruit block, but Case A (the stem
        // event) may not always run before this, or may fail (e.g. if AttachedStem
        // does not implement Directional). Registering here guarantees the fruit
        // always has its own direct DB entry — independent of the stem's existence.
        // Without this, the fruit appears "owned" only via the onBlockBreak fallback
        // (scanning adjacent stems), which breaks when the stem is later removed
        // (e.g. farmland below the stem is trampled).
        if (newType == Material.PUMPKIN || newType == Material.MELON) {
            if (storage.getBlockOwner(grownBlock) == null) {
                for (BlockFace face : new BlockFace[]{
                        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    Block stem = grownBlock.getRelative(face);
                    Material t = stem.getType();
                    if (t == Material.ATTACHED_PUMPKIN_STEM || t == Material.ATTACHED_MELON_STEM
                            || t == Material.PUMPKIN_STEM || t == Material.MELON_STEM) {
                        UUID stemOwner = storage.getBlockOwner(stem);
                        if (stemOwner != null) {
                            storage.setBlockOwner(grownBlock, stemOwner);
                            break;
                        }
                    }
                }
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
     *
     * @param event the event fired by the server
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
     *
     * <p>Also handles turtle egg laying via {@link EntityBlockFormEvent}:
     * if the laying turtle is owned (i.e. bred by a player), the egg block
     * is registered under the same owner. Wild turtles leave eggs unowned.
     * All other entity-formed blocks (frosted ice, silverfish stone, …) are
     * left unowned — they must not inherit ownership via neighbour scan.
     *
     * @param event the event fired by the server
     */
    @EventHandler
    public void onBlockForm(BlockFormEvent event) {
        Block formed = event.getBlock();
        String world = formed.getWorld().getName();

        // EntityBlockFormEvent: entity-caused block formation.
        // In Paper 1.21.8, turtle/frog egg-laying fires EntityChangeBlockEvent instead of
        // EntityBlockFormEvent — that case is handled in onEggLayerPlacesBlock() below.
        // All other EntityBlockFormEvents (frost walker, silverfish) are left unowned and
        // skip the neighbour scan below.
        if (event instanceof EntityBlockFormEvent) {
            return;
        }

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
     *
     * @param event the event fired by the server
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
     * Assigns ownership to a newly hatched turtle or tadpole (if its source block carried
     * a "fed by" entry) and cleans up block-level DB entries when the source block is gone.
     *
     * <p>There is no dedicated hatch event in Bukkit for either species. Instead, we listen
     * for {@link CreatureSpawnEvent} and schedule a 1-tick delayed check so Minecraft has
     * time to update the block type after the hatch.
     *
     * <p><b>Per-hatchling ownership:</b> Every entity that spawns reads the
     * {@code block_fedby} entry of the source block and writes it into its own
     * {@code entity_ownership} entry immediately (before the 1-tick delay), so all
     * siblings from a multi-egg turtle block each get the entry.
     *
     * <p><b>Frogspawn:</b> All tadpoles hatch at once (the whole block disappears together),
     * so the multi-hatch subtlety is less critical — but the same code path handles it.
     *
     * <p><b>Cleanup:</b> Once the source block is gone, both {@code block_ownership} and
     * {@code block_fedby} entries are removed. Turtle-egg blocks with multiple eggs keep
     * their entries until the very last egg hatches.
     *
     * <p>Wild / untracked spawns are filtered out implicitly: their source block has no
     * {@code block_fedby} entry, so {@code getBlockFedBy} returns {@code null}.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggHatch(CreatureSpawnEvent event) {
        boolean isTurtle  = event.getEntity() instanceof Turtle;
        boolean isTadpole = event.getEntity() instanceof Tadpole;
        if (!isTurtle && !isTadpole) return;

        // Locate the source egg block.
        //
        // In Paper 1.21.8 the egg block is removed (→ AIR) BEFORE the baby entity is
        // spawned, so by the time CreatureSpawnEvent fires the block type is already AIR.
        // Additionally, babies sometimes spawn with a sub-block Y offset that pushes
        // getBlock() one step above the egg position.
        //
        // Strategy: resolve the correct source position by checking two candidates in
        // order of preference:
        //   1. The exact spawn-location block   (spawnBlock)
        //   2. The block one step below          (spawnBlock - 1 Y)
        // "Has a relevant entry" means block_fedby ≠ null OR block_ownership ≠ null OR
        // the block still shows the egg material (egg hasn't broken yet).
        Block spawnBlock = event.getLocation().getBlock();
        final Material eggMaterial = isTurtle ? Material.TURTLE_EGG : Material.FROGSPAWN;

        final Block sourceBlock;
        if (spawnBlock.getType() == eggMaterial
                || storage.getBlockFedBy(spawnBlock) != null
                || storage.getBlockOwner(spawnBlock) != null) {
            sourceBlock = spawnBlock;
        } else {
            Block below = spawnBlock.getRelative(BlockFace.DOWN);
            if (below.getType() == eggMaterial
                    || storage.getBlockFedBy(below) != null
                    || storage.getBlockOwner(below) != null) {
                sourceBlock = below;
            } else {
                sourceBlock = spawnBlock; // no entry found at either position
            }
        }

        UUID entityId = event.getEntity().getUniqueId();

        if (isTurtle) {
            // Turtles: block_fedby → entity_ownership directly.
            // Baby turtles are immediately interactable (shearing etc. once grown),
            // so they get entity_ownership right away.
            UUID fedBy = storage.getBlockFedBy(sourceBlock);
            if (fedBy != null) {
                storage.setEntityOwner(entityId, fedBy);
            }
        } else {
            // Tadpoles — two-stage cycle:
            //   Cycle 1 (frogspawn has block_fedby only): tadpole gets entity_fedby.
            //     It is NOT yet bucketable. When it grows into a frog, onTadpoleGrow
            //     converts entity_fedby → entity_ownership on the adult frog.
            //   Cycle 2 (frogspawn also has block_ownership, from an owned parent frog):
            //     tadpole ALSO gets entity_ownership and is immediately bucketable.
            UUID fedBy = storage.getBlockFedBy(sourceBlock);
            if (fedBy != null) {
                storage.setEntityFedBy(entityId, fedBy);
            }
            UUID blockOwner = storage.getBlockOwner(sourceBlock);
            if (blockOwner != null) {
                storage.setEntityOwner(entityId, blockOwner);
            }
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Source block still present → siblings may still hatch; keep entries.
            if (sourceBlock.getType() == eggMaterial) return;
            // Block is gone → remove both block-level entries.
            storage.removeBlockOwner(sourceBlock);
            storage.removeBlockFedBy(sourceBlock);
        }, 1L);
    }

    /**
     * In Paper 1.21.8, turtle/frog egg-laying fires {@link EntityChangeBlockEvent} instead
     * of {@link EntityBlockFormEvent}.  This handler covers both species:
     *
     * <ul>
     *   <li>Turtle lays a TURTLE_EGG  → AIR becomes TURTLE_EGG</li>
     *   <li>Frog  lays a FROGSPAWN    → AIR becomes FROGSPAWN</li>
     * </ul>
     *
     * Same two-cycle logic as the original EntityBlockFormEvent handler:
     * if the layer has an {@code entity_fedby} marker the block gets {@code block_fedby};
     * if the layer is already owned the block also gets {@code block_ownership}.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggLayerPlacesBlock(EntityChangeBlockEvent event) {
        boolean isTurtleEgg = event.getEntity() instanceof Turtle && event.getTo() == Material.TURTLE_EGG;
        boolean isFrogspawn = event.getEntity() instanceof Frog   && event.getTo() == Material.FROGSPAWN;
        if (!isTurtleEgg && !isFrogspawn) return;

        Block formed = event.getBlock(); // position of the new egg/frogspawn block
        UUID layerUUID = event.getEntity().getUniqueId();

        UUID fedBy = storage.getEntityFedBy(layerUUID);
        if (fedBy != null) {
            storage.setBlockFedBy(formed, fedBy);
        }
        UUID owner = storage.getEntityOwner(layerUUID);
        if (owner != null) {
            storage.setBlockOwner(formed, owner);
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
                 MOSS_BLOCK,
                 // Berry bushes
                 SWEET_BERRY_BUSH,
                 // Kelp
                 KELP, KELP_PLANT -> true;
            default -> false;
        };
    }
}
