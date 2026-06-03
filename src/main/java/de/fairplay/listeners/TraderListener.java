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
import java.util.List;

/**
 * Customises the Wandering Trader to compensate for content that FairPlay's
 * ownership model would otherwise make inaccessible.
 *
 * <h2>Heart of the Sea</h2>
 * Buried treasure is unreachable in solo mode (ocean floor is unowned).
 * To preserve the Conduit progression, a Heart of the Sea trade is added:
 * <ul>
 *   <li>Cost: 8 × Nautilus Shell + 8 × Emerald</li>
 *   <li>Max uses: 1 per trader</li>
 * </ul>
 * Nautilus Shells are obtainable via treasure fishing, so the full loop
 * (fish shells → trade for heart → craft conduit) remains intact.
 *
 * <h2>Sniffer Egg</h2>
 * Suspicious Sand (Warm Ocean Ruins) and Suspicious Gravel (Trail Ruins)
 * cannot be brushed or mined directly in solo mode. However, both can be
 * obtained as empty items via a Soul Sand bubble-column technique:
 * surround/undermine the block using Moss-converted neighbours, place a
 * Soul Sand bubble column below, remove the support block — the Suspicious
 * block becomes a Falling Block entity that floats in the column until it
 * ages out (~30 s) and drops as an item (loot pool cleared at that point).
 * The item can then be traded here for a Sniffer Egg:
 * <ul>
 *   <li>Cost option A: 1 × Suspicious Sand   + 8 × Emerald → 1 × Sniffer Egg</li>
 *   <li>Cost option B: 1 × Suspicious Gravel + 8 × Emerald → 1 × Sniffer Egg</li>
 *   <li>Max uses: 1 per trader per trade</li>
 * </ul>
 */
public class TraderListener implements Listener {

    private static final int EMERALD_COST = 8;

    /**
     * Constructs a new TraderListener.
     */
    public TraderListener() {}

    /**
     * Appends FairPlay-specific trades to every Wandering Trader on spawn.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWanderingTraderSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)) return;

        List<MerchantRecipe> recipes = new ArrayList<>(trader.getRecipes());

        // ── Heart of the Sea ─────────────────────────────────────────────────
        MerchantRecipe heartTrade = new MerchantRecipe(
                new ItemStack(Material.HEART_OF_THE_SEA), 1);
        heartTrade.addIngredient(new ItemStack(Material.NAUTILUS_SHELL, 8));
        heartTrade.addIngredient(new ItemStack(Material.EMERALD, EMERALD_COST));
        recipes.add(heartTrade);

        // ── Sniffer Egg (via Suspicious Sand — Warm Ocean Ruins) ─────────────
        MerchantRecipe snifferFromSand = new MerchantRecipe(
                new ItemStack(Material.SNIFFER_EGG), 1);
        snifferFromSand.addIngredient(new ItemStack(Material.SUSPICIOUS_SAND, 1));
        snifferFromSand.addIngredient(new ItemStack(Material.EMERALD, EMERALD_COST));
        recipes.add(snifferFromSand);

        // ── Sniffer Egg (via Suspicious Gravel — Trail Ruins) ────────────────
        MerchantRecipe snifferFromGravel = new MerchantRecipe(
                new ItemStack(Material.SNIFFER_EGG), 1);
        snifferFromGravel.addIngredient(new ItemStack(Material.SUSPICIOUS_GRAVEL, 1));
        snifferFromGravel.addIngredient(new ItemStack(Material.EMERALD, EMERALD_COST));
        recipes.add(snifferFromGravel);

        trader.setRecipes(recipes);
    }
}
