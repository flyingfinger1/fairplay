package de.fairplay.listeners;

import org.bukkit.Material;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Adds FairPlay-specific trades to every Wandering Trader.
 *
 * <p>Each Wandering Trader receives {@value #FAIR_TRADES_PER_TRADER} randomly
 * chosen trades from the FairTrades pool — a curated list of items that are
 * otherwise unreachable in FairPlay's solo mode (structure-chest loot is cleared,
 * unowned blocks cannot be mined or brushed, Ancient Debris is blast-resistant).
 *
 * <h2>Pool contents and costs</h2>
 * <table>
 *   <tr><th>Item</th><th>Cost</th><th>Why unreachable</th></tr>
 *   <tr><td>Heart of the Sea</td><td>8 Nautilus Shell + 8 Emerald</td><td>Buried treasure chest</td></tr>
 *   <tr><td>Sniffer Egg</td><td>1 Suspicious Sand/Gravel + 8 Emerald</td><td>Archaeology only</td></tr>
 *   <tr><td>Ancient Debris</td><td>8 Emerald + 1 Diamond</td><td>Unowned nether block, blast-resistant</td></tr>
 *   <tr><td>Smithing Templates (all)</td><td>8 Emerald + 8 Diamond</td><td>Structure chests cleared</td></tr>
 *   <tr><td>Pottery Sherds (all)</td><td>8 Emerald + 8 Brick</td><td>Archaeology only</td></tr>
 *   <tr><td>Newer Music Discs</td><td>8 Emerald + 8 Sculk</td><td>Structure chests cleared / archaeology</td></tr>
 * </table>
 *
 * <p>Smithing Templates and Pottery Sherds are discovered dynamically via
 * {@link Material#values()} so future Minecraft additions are included automatically.
 */
public class TraderListener implements Listener {

    /** Number of FairTrades randomly selected and shown on each Wandering Trader. */
    private static final int FAIR_TRADES_PER_TRADER = 4;

    /**
     * Holds the parameters for one trade. A new {@link MerchantRecipe} is created
     * per trader via {@link #build()} so that use-counts are never shared.
     */
    private record TradeSpec(
            Material result,
            Material ingredient1, int amount1,
            Material ingredient2, int amount2
    ) {
        MerchantRecipe build() {
            MerchantRecipe recipe = new MerchantRecipe(new ItemStack(result), 1);
            recipe.addIngredient(new ItemStack(ingredient1, amount1));
            recipe.addIngredient(new ItemStack(ingredient2, amount2));
            return recipe;
        }
    }

    /**
     * Music discs that are only obtainable from structure chests or archaeology in vanilla.
     * The classic discs (13, cat, blocks, …) are still obtainable via Skeleton-kills-Creeper.
     */
    private static final Set<Material> NEWER_MUSIC_DISCS = Set.of(
            Material.MUSIC_DISC_PIGSTEP,
            Material.MUSIC_DISC_OTHERSIDE,
            Material.MUSIC_DISC_RELIC,
            Material.MUSIC_DISC_5,
            Material.MUSIC_DISC_CREATOR,
            Material.MUSIC_DISC_CREATOR_MUSIC_BOX,
            Material.MUSIC_DISC_PRECIPICE
    );

    private static final List<TradeSpec> FAIR_TRADE_POOL = new ArrayList<>();

    static {
        // ── Heart of the Sea ─────────────────────────────────────────────────
        // Nautilus Shells from treasure fishing; Heart normally in buried-treasure chest.
        FAIR_TRADE_POOL.add(new TradeSpec(
                Material.HEART_OF_THE_SEA,
                Material.NAUTILUS_SHELL, 8,
                Material.EMERALD, 8));

        // ── Sniffer Egg ───────────────────────────────────────────────────────
        // Suspicious Sand/Gravel obtained via Soul Sand bubble-column technique:
        // undermine the block (Moss-converted neighbours), place a bubble column,
        // Suspicious block becomes a Falling Block that ages out (~30 s) as an item.
        FAIR_TRADE_POOL.add(new TradeSpec(
                Material.SNIFFER_EGG,
                Material.SUSPICIOUS_SAND, 1,
                Material.EMERALD, 8));
        FAIR_TRADE_POOL.add(new TradeSpec(
                Material.SNIFFER_EGG,
                Material.SUSPICIOUS_GRAVEL, 1,
                Material.EMERALD, 8));

        // ── Ancient Debris ────────────────────────────────────────────────────
        // Unowned Nether block; blast resistance 1200 → explosion workaround impossible.
        FAIR_TRADE_POOL.add(new TradeSpec(
                Material.ANCIENT_DEBRIS,
                Material.EMERALD, 8,
                Material.DIAMOND, 1));

        // ── Smithing Templates (all) ──────────────────────────────────────────
        // Found only in structure chests, which are cleared by LootListener.
        // Discovered dynamically so new templates in future versions are included.
        for (Material m : Material.values()) {
            if (m.name().endsWith("_SMITHING_TEMPLATE")) {
                FAIR_TRADE_POOL.add(new TradeSpec(
                        m,
                        Material.EMERALD, 8,
                        Material.DIAMOND, 8));
            }
        }

        // ── Pottery Sherds (all) ──────────────────────────────────────────────
        // Obtained only via brushing Suspicious Sand/Gravel, blocked in solo mode.
        // Discovered dynamically so new sherds in future versions are included.
        // Cost: Bricks (smelted from Clay Balls — the raw material of pottery).
        for (Material m : Material.values()) {
            if (m.name().endsWith("_POTTERY_SHERD")) {
                FAIR_TRADE_POOL.add(new TradeSpec(
                        m,
                        Material.EMERALD, 8,
                        Material.BRICK, 8));
            }
        }

        // ── Newer Music Discs ─────────────────────────────────────────────────
        // Pigstep/Otherside/Relic/5/Creator/Precipice are chest-loot or archaeology only.
        // Classic discs (13, cat, …) remain obtainable via Skeleton-kills-Creeper.
        // Cost: Sculk (obtainable from player-placed Sculk Catalyst + mob death nearby,
        //        or from Warden drops).
        for (Material m : NEWER_MUSIC_DISCS) {
            FAIR_TRADE_POOL.add(new TradeSpec(
                    m,
                    Material.EMERALD, 8,
                    Material.SCULK, 8));
        }
    }

    /**
     * Constructs a new TraderListener.
     */
    public TraderListener() {}

    /**
     * Appends {@value #FAIR_TRADES_PER_TRADER} randomly selected FairTrades to every
     * Wandering Trader that spawns. Each trader gets a different selection so players
     * must encounter multiple traders to access the full pool.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWanderingTraderSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)) return;

        List<TradeSpec> pool = new ArrayList<>(FAIR_TRADE_POOL);
        Collections.shuffle(pool);

        List<MerchantRecipe> recipes = new ArrayList<>(trader.getRecipes());
        pool.stream()
                .limit(FAIR_TRADES_PER_TRADER)
                .map(TradeSpec::build)
                .forEach(recipes::add);
        trader.setRecipes(recipes);
    }
}
