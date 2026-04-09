package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;

import java.util.Collections;

/**
 * Clears all naturally generated loot (chests, barrels, end cities, …).
 * FairPlay is a resource-gathering game — loot containers would bypass ownership.
 * Opening an empty container for the first time grants the "empty_hands" advancement.
 */
public class LootListener implements Listener {

    private final AdvancementManager adv;

    /**
     * Constructs a new LootListener with the given advancement manager.
     *
     * @param adv the advancement manager used to grant advancements
     */
    public LootListener(AdvancementManager adv) {
        this.adv = adv;
    }

    /**
     * Replaces the generated loot list with an empty list, preventing any items from
     * appearing. Grants "empty_hands" to the player who opened the container.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLootGenerate(LootGenerateEvent event) {
        event.setLoot(Collections.emptyList());

        if (event.getEntity() instanceof Player player) {
            adv.grant(player, "empty_hands");
        }
    }
}
