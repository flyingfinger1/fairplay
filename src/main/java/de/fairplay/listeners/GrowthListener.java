package de.fairplay.listeners;

import de.fairplay.Lang;
import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.Set;
import org.bukkit.block.BlockState;
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

    public GrowthListener(JavaPlugin plugin, OwnershipStorage storage, AdvancementManager adv) {
        this.plugin = plugin;
        this.storage = storage;
        this.adv = adv;
    }

    /**
     * Baum, Pilz, Bambus usw. wächst aus einem platzierten Setzling/Spore.
     * Alle neu generierten Blöcke erhalten die Ownership des Setzlings.
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
        if (player != null) adv.grant(player, "mein_wald");
    }

    /**
     * Knochenmehr auf einem eigenen Block:
     *  - Nicht eigener Block → Aktion wird blockiert
     *  - Eigener Block → alle generierten Blöcke werden dem Spieler zugewiesen
     * Gilt für Moos, PaleMoos, Gras, Getreide, Seerose usw.
     */
    @EventHandler
    public void onBlockFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null) return; // Werfer – kein Ownership-Check

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

    // Pflanzen bei denen BlockGrowEvent auf dem bestehenden Tip feuert,
    // der neue Block aber erst einen Tick später erscheint.
    private static final Set<Material> DELAYED_GROW_PLANTS = Set.of(
        Material.BAMBOO, Material.BAMBOO_SAPLING,
        Material.KELP,   Material.KELP_PLANT
    );

    /**
     * Wächst ein Block nach oben oder unten:
     *  - Nach oben (Zuckerrohr, Kaktus, Twisting Vines): Ownership vom Block darunter
     *  - Nach unten (Cave Vines, Weeping Vines): Ownership vom Block darüber
     * Getreide/Kartoffeln wachsen an derselben Koordinate – DB-Eintrag bleibt automatisch.
     *
     * Bambus/Kelp-Sonderfall: BlockGrowEvent feuert für die bestehende Spitze
     * (Altersänderung), bevor der neue Block darüber erscheint.
     * Lösung: 1 Tick warten, dann den Block darüber registrieren.
     */
    @EventHandler
    public void onBlockGrow(BlockGrowEvent event) {
        Block grownBlock = event.getBlock();

        // Neuer Block erscheint oben (Zuckerrohr, Kaktus, ...)
        UUID owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.DOWN));
        if (owner != null) {
            storage.setBlockOwner(grownBlock, owner);
            return;
        }

        // Neuer Block erscheint unten (Cave Vines, Weeping Vines)
        owner = storage.getBlockOwner(grownBlock.getRelative(BlockFace.UP));
        if (owner != null) {
            storage.setBlockOwner(grownBlock, owner);
            return;
        }

        // Bambus / Kelp: Event feuert auf bestehende Spitze → neuer Block erscheint
        // erst nach dem Event. 1 Tick warten, dann den Block darüber registrieren.
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
     * Ausbreitung von Organismen auf benachbarte Blöcke.
     * Übertragung nur für Pflanzen/Organismen bei denen das inhaltlich sinnvoll ist.
     * Gras/Myzel werden bewusst ausgeschlossen (der Spieler besitzt den Ziel-Dirt-Block
     * bereits, falls er ihn selbst platziert hat).
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
     * Cobblestone-/Obsidian-/Stein-Generator: mindestens eine der beteiligten
     * Quellen (Lava oder Wasser) muss vom Spieler platziert worden sein.
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
                    if (player != null) adv.grant(player, "stein_ohne_ende");
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
                            adv.grant(player, "unendlich");
                        }
                    }
                }

                return;
            }
        }
    }

    /**
     * Materialien, deren Ausbreitung Ownership überträgt.
     */
    private boolean transfersOwnership(Material material) {
        return switch (material) {
            case PUMPKIN_STEM, ATTACHED_PUMPKIN_STEM,
                 MELON_STEM, ATTACHED_MELON_STEM,
                 // Sculk
                 SCULK, SCULK_CATALYST, SCULK_SENSOR, SCULK_SHRIEKER, SCULK_VEIN,
                 // Ranken
                 VINE,
                 CAVE_VINES, CAVE_VINES_PLANT,
                 WEEPING_VINES, WEEPING_VINES_PLANT,
                 TWISTING_VINES, TWISTING_VINES_PLANT,
                 // Moos
                 MOSS_BLOCK, PALE_MOSS_BLOCK,
                 // Beeren-Büsche
                 SWEET_BERRY_BUSH,
                 // Kelp
                 KELP, KELP_PLANT -> true;
            default -> false;
        };
    }
}
