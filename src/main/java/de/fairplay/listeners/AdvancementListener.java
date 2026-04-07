package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.entity.Creeper;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.event.player.*;
import org.bukkit.inventory.MerchantInventory;

import java.util.Set;

/**
 * Grants FairPlay advancements in response to various game events.
 *
 * <p>Each inner section corresponds to one or more advancements:
 * <ul>
 *   <li><b>root</b> — granted to every player on join</li>
 *   <li><b>collector / foundation / green_thumb / bed_to_go</b> — item pickups</li>
 *   <li><b>diy</b> — crafting any item</li>
 *   <li><b>endless_iron / resourcefulness / bodyguard</b> — mob deaths</li>
 *   <li><b>mans_best_friend</b> — taming a wolf</li>
 *   <li><b>good_night</b> — entering a bed</li>
 *   <li><b>fair_trade</b> — trading with a villager</li>
 *   <li><b>water_management / hot_lava</b> — placing water or lava</li>
 *   <li><b>ignition</b> — a creeper explodes nearby</li>
 *   <li><b>first_night</b> — surviving until dawn (time-based, checked every second)</li>
 * </ul>
 *
 * <p>Vanilla advancement announcements are suppressed globally via the
 * {@code announceAdvancements} game rule set in {@link de.fairplay.FairPlayPlugin}.
 */
public class AdvancementListener implements Listener {

    // Materials that count as "solid blocks" for the Foundation advancement
    private static final Set<Material> SOLID_BLOCKS = Set.of(
        Material.STONE, Material.COBBLESTONE, Material.DIRT, Material.GRASS_BLOCK,
        Material.OAK_LOG, Material.OAK_PLANKS, Material.SAND, Material.GRAVEL,
        Material.IRON_ORE, Material.COAL_ORE, Material.COPPER_ORE, Material.GOLD_ORE,
        Material.NETHERRACK, Material.SANDSTONE, Material.GRANITE, Material.DIORITE,
        Material.ANDESITE, Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
        Material.SMOOTH_STONE, Material.COBBLED_DEEPSLATE
    );

    // Sapling materials for Green Thumb
    private static final Set<Material> SAPLINGS = Set.of(
        Material.OAK_SAPLING, Material.SPRUCE_SAPLING, Material.BIRCH_SAPLING,
        Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
        Material.CHERRY_SAPLING, Material.MANGROVE_PROPAGULE, Material.AZALEA,
        Material.FLOWERING_AZALEA, Material.PALE_OAK_SAPLING
    );

    // Bed materials for Bed To Go
    private static final Set<Material> BEDS = Set.of(
        Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED,
        Material.LIGHT_BLUE_BED, Material.YELLOW_BED, Material.LIME_BED,
        Material.PINK_BED, Material.GRAY_BED, Material.LIGHT_GRAY_BED,
        Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
        Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED
    );

    private final AdvancementManager adv;

    public AdvancementListener(AdvancementManager adv, JavaPlugin plugin) {
        this.adv = adv;
        startDawnDetector(plugin);
    }

    // ── First Night ──────────────────────────────────────────────────────────

    /**
     * Detects dawn by tracking the world time every second.
     * Grants "first_night" when the time transitions from night (>12000) to
     * morning (<1000) — covers both natural survival and sleeping.
     */
    private void startDawnDetector(JavaPlugin plugin) {
        final Map<World, Long> lastTime = new HashMap<>();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : plugin.getServer().getWorlds()) {
                    if (world.getEnvironment() != World.Environment.NORMAL) continue;
                    long time = world.getTime();
                    long prev = lastTime.getOrDefault(world, time);
                    lastTime.put(world, time);

                    // Transition from night (>12000) to dawn (<1000): grant to all alive players
                    if (prev > 12000 && time < 1000) {
                        for (Player p : world.getPlayers()) {
                            if (p.isOnline() && !p.isDead()) {
                                adv.grant(p, "first_night");
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ── Root ─────────────────────────────────────────────────────────────────

    /** Grants the hidden root advancement to every player on join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        adv.grant(event.getPlayer(), "root");
    }

    // ── Collector / Foundation / Green Thumb / Bed To Go ─────────────────────

    /** Checks item type on pickup and grants collector / foundation / green_thumb / bed_to_go. */
    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Material type = event.getItem().getItemStack().getType();

        adv.grant(player, "collector");

        if (SOLID_BLOCKS.contains(type)) adv.grant(player, "foundation");
        if (SAPLINGS.contains(type))     adv.grant(player, "green_thumb");
        if (BEDS.contains(type))         adv.grant(player, "bed_to_go");
    }

    // ── DIY ──────────────────────────────────────────────────────────────────

    /** Grants "diy" the first time a player crafts anything. */
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        adv.grant(player, "diy");
    }

    // ── Endless Iron / Resourcefulness / Bodyguard ───────────────────────────

    /**
     * Handles mob deaths for advancement triggers:
     * <ul>
     *   <li><b>endless_iron</b> — a wild iron golem dies near a player</li>
     *   <li><b>resourcefulness</b> — a hostile mob dies without a direct player kill</li>
     *   <li><b>bodyguard</b> — a hostile mob is killed by the player's wolf</li>
     * </ul>
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        // Endless Iron: wild iron golem dies near a player
        if (event.getEntity() instanceof IronGolem golem && !golem.isPlayerCreated()) {
            golem.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(golem.getLocation()) < 256)
                .findFirst()
                .ifPresent(p -> adv.grant(p, "endless_iron"));
        }

        // Resourcefulness: hostile mob killed, not by direct player attack
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

    // ── Man's Best Friend ────────────────────────────────────────────────────

    /** Grants "mans_best_friend" when a player tames a wolf. */
    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getEntity() instanceof Wolf && event.getOwner() instanceof Player player) {
            adv.grant(player, "mans_best_friend");
        }
    }

    // ── Good Night ───────────────────────────────────────────────────────────

    /** Grants "good_night" when a player successfully enters a bed. */
    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            adv.grant(event.getPlayer(), "good_night");
        }
    }

    // ── Fair Trade ───────────────────────────────────────────────────────────

    /** Grants "fair_trade" when a player makes a villager trade. */
    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory() instanceof MerchantInventory) {
            adv.grant(player, "fair_trade");
        }
    }

    // ── Water Management / Hot Lava ──────────────────────────────────────────

    /** Grants "water_management" or "hot_lava" when a player places water or lava. */
    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Material bucket = event.getBucket();
        if (bucket == Material.WATER_BUCKET) adv.grant(player, "water_management");
        if (bucket == Material.LAVA_BUCKET)  adv.grant(player, "hot_lava");
    }

    // ── Ignition ──────────────────────────────────────────────────────────────

    /** Grants "ignition" to the nearest player (within 20 blocks) when a creeper explodes. */
    @EventHandler
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper)) return;
        if (event.blockList().isEmpty()) return;

        // Grant to the nearest player within 20 blocks
        event.getLocation().getWorld().getPlayers().stream()
            .filter(p -> p.getLocation().distanceSquared(event.getLocation()) <= 400)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(event.getLocation())))
            .ifPresent(p -> adv.grant(p, "ignition"));
    }

    // ── Vanilla Advancements ──────────────────────────────────────────────────
    // Criteria are NOT revoked – doing so would cause an infinite loop:
    // revoke → advancement resets → next action triggers it again → etc.
    // Instead: announceAdvancements=false (gamerule in FairPlayPlugin) suppresses
    // chat messages. Vanilla advancements complete once and are never shown again.
}
