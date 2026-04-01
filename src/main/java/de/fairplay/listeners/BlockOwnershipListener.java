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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Set;
import java.util.UUID;

public class BlockOwnershipListener implements Listener {

    private static final Set<Material> CROP_MATERIALS = Set.of(
        Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
        Material.NETHER_WART, Material.MELON_STEM, Material.PUMPKIN_STEM
    );

    private final OwnershipStorage storage;
    private final AdvancementManager adv;

    public BlockOwnershipListener(OwnershipStorage storage, AdvancementManager adv) {
        this.storage = storage;
        this.adv = adv;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlock();
        UUID playerUUID = event.getPlayer().getUniqueId();
        storage.setBlockOwner(placed, playerUUID);

        BlockData data = placed.getBlockData();

        // Bett: Kopfteil mitregistrieren
        if (data instanceof Bed bed && bed.getPart() == Bed.Part.FOOT) {
            storage.setBlockOwner(placed.getRelative(bed.getFacing()), playerUUID);

        // Türen & hohe Pflanzen (Bisected): obere Hälfte mitregistrieren
        } else if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.BOTTOM) {
            storage.setBlockOwner(placed.getRelative(BlockFace.UP), playerUUID);
        }

        // Koch: furnace types
        if (placed.getType() == Material.FURNACE
                || placed.getType() == Material.BLAST_FURNACE
                || placed.getType() == Material.SMOKER) {
            adv.grant(event.getPlayer(), "koch");
        }

        // Aussaat: crop blocks
        if (placed.getBlockData() instanceof Ageable && isCropMaterial(placed.getType())) {
            adv.grant(event.getPlayer(), "aussaat");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        // Creative-Spieler dürfen alles abbauen
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

        // Bambus-Fallback: gewachsene Blöcke ohne Owner-Eintrag → Stamm abwärts absuchen
        if (owner == null && (block.getType() == Material.BAMBOO
                || block.getType() == Material.BAMBOO_SAPLING)) {
            Block check = block.getRelative(BlockFace.DOWN);
            while (owner == null && (check.getType() == Material.BAMBOO
                    || check.getType() == Material.BAMBOO_SAPLING)) {
                owner = storage.getBlockOwner(check);
                check = check.getRelative(BlockFace.DOWN);
            }
        }

        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "fremdes_eigentum");
            return;
        }

        adv.grant(player, "eigene_haende");

        // Ernte: fully grown crop
        if (block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() == ageable.getMaximumAge()) {
            adv.grant(player, "ernte");
        }

        storage.removeBlockOwner(block);

        // Zweiblock-Strukturen: auch den jeweils anderen Teil aus der DB entfernen
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
     * Wasser- und Lava-Eimer registrieren Ownership des platzierten Quellblocks.
     * BlockPlaceEvent feuert für Eimer-Platzierungen nicht zuverlässig, daher
     * wird die Ownership hier explizit beim Leeren des Eimers gespeichert.
     * MONITOR-Priorität: läuft nach allen anderen Listenern, nur wenn nicht gecancelt.
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
     * Wasser- und Lavaeimer dürfen nur aus eigenen Quellen gefüllt werden.
     * Gilt sowohl für reine Wasser-/Lavablöcke als auch für Waterlogged-Blöcke
     * (z.B. Zaun im Wasser): dort wird geprüft ob ein eigener Wasser-Quellblock
     * angrenzt.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        Material filled = block.getType();

        // Direkter Wasser- oder Lavaquellblock
        if (filled == Material.WATER || filled == Material.LAVA) {
            UUID owner = storage.getBlockOwner(block);
            if (owner == null || !owner.equals(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendActionBar(Lang.get(player, "msg.bucket"));
            }
            return;
        }

        // Waterlogged-Block (z.B. Zaun, Treppe, Slab im Wasser):
        // Erlaubt wenn mindestens ein angrenzender eigener Wasser-Quellblock existiert.
        if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
            UUID playerId = player.getUniqueId();
            for (BlockFace face : WATER_CHECK_FACES) {
                Block neighbor = block.getRelative(face);
                if (neighbor.getType() == Material.WATER
                        && playerId.equals(storage.getBlockOwner(neighbor))) {
                    return; // eigenes Wasser angrenzt → erlaubt
                }
            }
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.bucket"));
        }
    }

    /**
     * Glasflaschen dürfen nur an eigenen Wasser-Quellblöcken gefüllt werden.
     * Der Kessel-Fall (BOTTLE_FILL) ist bereits im CauldronListener abgedeckt.
     * OffHand-Events werden ignoriert um Doppelverarbeitung zu vermeiden.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBottleFill(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        var item = event.getItem();
        if (item == null || item.getType() != Material.GLASS_BOTTLE) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.WATER) return;

        Player player = event.getPlayer();
        UUID owner = storage.getBlockOwner(block);

        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.bucket"));
        }
    }

    /**
     * Süßbeeren-Ernte: Right-Click auf fremden Beerenbusch blockieren.
     * Der Busch bleibt stehen, aber die Beeren gehören dem Eigentümer.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBerryPick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SWEET_BERRY_BUSH) return;

        // Busch mit age < 2 trägt keine Beeren → kein Item, kein Check nötig
        if (!(block.getBlockData() instanceof Ageable ageable) || ageable.getAge() < 2) return;

        Player player = event.getPlayer();
        UUID owner = storage.getBlockOwner(block);
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "fremdes_eigentum");
        }
    }

    /**
     * Felder zertrampeln: EntityChangeBlockEvent feuert wenn FARMLAND → DIRT wird.
     * Nur Spieler werden geprüft; andere Entities (Tiere etc.) werden ignoriert.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getBlock().getType() != Material.FARMLAND) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID owner = storage.getBlockOwner(event.getBlock());
        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(Lang.get(player, "msg.break"));
            adv.grant(player, "fremdes_eigentum");
        }
    }

    private boolean isCropMaterial(Material m) {
        return CROP_MATERIALS.contains(m);
    }
}
