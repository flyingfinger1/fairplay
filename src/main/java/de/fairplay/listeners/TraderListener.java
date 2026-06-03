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
 * To preserve the Conduit progression, a single Heart of the Sea trade is
 * appended to every Wandering Trader that spawns:
 * <ul>
 *   <li>Cost: 8 × Nautilus Shell + 8 × Emerald</li>
 *   <li>Max uses: 1 per trader</li>
 * </ul>
 * Nautilus Shells are obtainable via treasure fishing, so the full loop
 * (fish shells → trade for heart → craft conduit) remains intact.
 */
public class TraderListener implements Listener {

    private static final int NAUTILUS_COST = 8;
    private static final int EMERALD_COST  = 8;

    /**
     * Constructs a new TraderListener.
     */
    public TraderListener() {}

    /**
     * Appends the Heart of the Sea trade to every Wandering Trader on spawn.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWanderingTraderSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)) return;

        MerchantRecipe heartTrade = new MerchantRecipe(
                new ItemStack(Material.HEART_OF_THE_SEA), 1);
        heartTrade.addIngredient(new ItemStack(Material.NAUTILUS_SHELL, NAUTILUS_COST));
        heartTrade.addIngredient(new ItemStack(Material.EMERALD, EMERALD_COST));

        List<MerchantRecipe> recipes = new ArrayList<>(trader.getRecipes());
        recipes.add(heartTrade);
        trader.setRecipes(recipes);
    }
}
