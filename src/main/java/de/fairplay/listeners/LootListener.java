package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;

import java.util.Collections;

public class LootListener implements Listener {

    private final AdvancementManager adv;

    public LootListener(AdvancementManager adv) {
        this.adv = adv;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLootGenerate(LootGenerateEvent event) {
        event.setLoot(Collections.emptyList());

        if (event.getEntity() instanceof Player player) {
            adv.grant(player, "leere_haende");
        }
    }
}
