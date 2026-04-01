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
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

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

        // New block appears above (sugar cane, cactus, ...)
        UUID owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.DOWN));
        if (owner != null) {
            storage.setBlockOwner(grownBlock, owner);
            return;
        }

        // New block appears below (cave vines, weeping vines)
        owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.UP));
        if (owner != null) {
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

        // Case B – fruit-side event: BlockSpreadEvent (subtype of BlockGrowEvent) fires
        // with event.getBlock() = the fruit position and getSource() = the stem.
        // event.getNewState().getType() == MELON / PUMPKIN here, not the stem type.
        if ((newType == Material.MELON || newType == Material.PUMPKIN)
                && event instanceof BlockSpreadEvent spread) {
            owner = storage.getBlockOwner(spread.getSource()); // stem owner
            if (owner != null) {
                storage.setBlockOwner(grownBlock, owner);
            }
            return;
        }

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

        for (BlockFace face : ALL_FACES) {
            Block neighbor = formed.getRelative(face);
            UUID owner = storage.getBlockOwner(world, neighbor.getX(), neighbor.getY(), neighbor.getZ());
            if (owner != null) {
                storage.setBlockOwner(formed, owner);

                // Stein ohne Ende: cobblestone/stone forms from lava+water
                if (event.getNewState().getType() == Material.COBBLESTONE
                        || event.getNewState().getType() == Material.STONE
                        || event.getNewState().getType() == Material.COBBLED_DEEPSLATE) {
                    Player player = plugin.getServer().getPlayer(owner);
                    if (player != null) adv.grant(player, "endless_stone");
                }

                // Unendlich: new water source block forms with 2+ adjacent owned water sources
                if (event.getNewState().getType() == Material.WATER) {
                    Player player = plugin.getServer().getPlayer(owner);
                    if (player != null) {
                        int ownedWaterNeighbors = 0;
                        for (BlockFace checkFace : ALL_FACES) {
                            Block checkNeighbor = formed.getRelative(checkFace);
                            if (checkNeighbor.getType() == Material.WATER) {
                                UUID neighborOwner = storage.getBlockOwner(world,
                                    checkNeighbor.getX(), checkNeighbor.getY(), checkNeighbor.getZ());
                                if (owner.equals(neighborOwner)) {
                                    ownedWaterNeighbors++;
                                }
                            }
                        }
                        if (ownedWaterNeighbors >= 2) {
                            adv.grant(player, "infinite");
                        }
                    }
                }

                return;
            }
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
