package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.entity.Creeper;
import org.bukkit.event.inventory.CraftItemEvent;
import java.util.Comparator;
import org.bukkit.event.player.*;
import org.bukkit.inventory.MerchantInventory;

import java.util.Set;

public class AdvancementListener implements Listener {

    // Materials that count as "solid blocks" for the Grundstein advancement
    private static final Set<Material> SOLID_BLOCKS = Set.of(
        Material.STONE, Material.COBBLESTONE, Material.DIRT, Material.GRASS_BLOCK,
        Material.OAK_LOG, Material.OAK_PLANKS, Material.SAND, Material.GRAVEL,
        Material.IRON_ORE, Material.COAL_ORE, Material.COPPER_ORE, Material.GOLD_ORE,
        Material.NETHERRACK, Material.SANDSTONE, Material.GRANITE, Material.DIORITE,
        Material.ANDESITE, Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
        Material.SMOOTH_STONE, Material.COBBLED_DEEPSLATE
    );

    // Sapling materials for Grüner Daumen
    private static final Set<Material> SAPLINGS = Set.of(
        Material.OAK_SAPLING, Material.SPRUCE_SAPLING, Material.BIRCH_SAPLING,
        Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
        Material.CHERRY_SAPLING, Material.MANGROVE_PROPAGULE, Material.AZALEA,
        Material.FLOWERING_AZALEA, Material.PALE_OAK_SAPLING
    );

    // Bed materials for Bett-To-Go
    private static final Set<Material> BEDS = Set.of(
        Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED,
        Material.LIGHT_BLUE_BED, Material.YELLOW_BED, Material.LIME_BED,
        Material.PINK_BED, Material.GRAY_BED, Material.LIGHT_GRAY_BED,
        Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
        Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED
    );

    private final AdvancementManager adv;

    public AdvancementListener(AdvancementManager adv) {
        this.adv = adv;
    }

    // ── Root ─────────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        adv.grant(event.getPlayer(), "root");
    }

    // ── Sammler / Grundstein / Grüner Daumen / Bett-To-Go ────────────────────

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Material type = event.getItem().getItemStack().getType();

        adv.grant(player, "collector");

        if (SOLID_BLOCKS.contains(type)) adv.grant(player, "foundation");
        if (SAPLINGS.contains(type))     adv.grant(player, "green_thumb");
        if (BEDS.contains(type))         adv.grant(player, "bed_to_go");
    }

    // ── Selbst ist der Mann ───────────────────────────────────────────────────

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        adv.grant(player, "diy");
    }

    // ── Eisen ohne Ende? / Einfallsreichtum / Bodyguard ──────────────────────

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        // Eisen ohne Ende: wild iron golem dies near a player
        if (event.getEntity() instanceof IronGolem golem && !golem.isPlayerCreated()) {
            golem.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(golem.getLocation()) < 256)
                .findFirst()
                .ifPresent(p -> adv.grant(p, "endless_iron"));
        }

        // Einfallsreichtum: hostile mob killed, not by direct player attack
        LivingEntity entity = event.getEntity();
        if (entity instanceof Monster) {
            EntityDamageEvent lastDamage = entity.getLastDamageCause();
            boolean directPlayerKill = lastDamage instanceof EntityDamageByEntityEvent dmgEvent
                && (dmgEvent.getDamager() instanceof Player
                    || (dmgEvent.getDamager() instanceof Projectile proj
                        && proj.getShooter() instanceof Player));

            if (!directPlayerKill) {
                entity.getWorld().getPlayers().stream()
                    .filter(p -> p.getLocation().distanceSquared(entity.getLocation()) < 256)
                    .findFirst()
                    .ifPresent(p -> adv.grant(p, "resourcefulness"));
            }
        }

        // Bodyguard: mob killed by a wolf whose owner is online
        if (entity instanceof Monster && entity.getLastDamageCause() instanceof EntityDamageByEntityEvent dmg) {
            Entity damager = dmg.getDamager();
            if (damager instanceof Wolf wolf && wolf.getOwner() instanceof Player owner) {
                adv.grant(owner, "bodyguard");
            }
        }
    }

    // ── Des Menschen bester Freund ────────────────────────────────────────────

    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getEntity() instanceof Wolf && event.getOwner() instanceof Player player) {
            adv.grant(player, "mans_best_friend");
        }
    }

    // ── Gute Nacht ────────────────────────────────────────────────────────────

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            adv.grant(event.getPlayer(), "good_night");
        }
    }

    // ── Fairer Handel ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory() instanceof MerchantInventory) {
            adv.grant(player, "fair_trade");
        }
    }

    // ── Wasserwirtschaft / Heiße Lava ─────────────────────────────────────────

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Material bucket = event.getBucket();
        if (bucket == Material.WATER_BUCKET) adv.grant(player, "water_management");
        if (bucket == Material.LAVA_BUCKET)  adv.grant(player, "hot_lava");
    }

    // ── Züüündung ─────────────────────────────────────────────────────────────

    @EventHandler
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper)) return;
        if (event.blockList().isEmpty()) return;

        // Dem nächsten Spieler in Reichweite (20 Blöcke) das Advancement vergeben
        event.getLocation().getWorld().getPlayers().stream()
            .filter(p -> p.getLocation().distanceSquared(event.getLocation()) <= 400)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(event.getLocation())))
            .ifPresent(p -> adv.grant(p, "ignition"));
    }

    // ── Vanilla Advancements deaktivieren ─────────────────────────────────────
    // Kriterien NICHT revoken – das würde einen Infinite-Loop erzeugen:
    // revoke → Advancement resettet → nächste Aktion triggert es erneut → etc.
    // Stattdessen: announceAdvancements=false (Gamerule in FairPlayPlugin) verhindert
    // Chat-Nachrichten. Vanilla-Advancements werden einmalig abgeschlossen und nie wieder
    // angezeigt.
}
