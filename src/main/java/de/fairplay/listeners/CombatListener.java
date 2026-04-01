package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

public class CombatListener implements Listener {

    private final OwnershipStorage storage;
    private final AdvancementManager adv;

    public CombatListener(OwnershipStorage storage, AdvancementManager adv) {
        this.storage = storage;
        this.adv = adv;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerAttacker(event.getDamager());
        if (attacker == null) return;

        Entity target = event.getEntity();

        // Players may destroy their own vehicles
        if (isVehicle(target)) {
            UUID owner = storage.getEntityOwner(target.getUniqueId());
            if (owner != null && owner.equals(attacker.getUniqueId())) {
                return;
            }
        }

        // Block all other direct attacks
        event.setCancelled(true);

        // Keine Gewalt: direct attack on a mob (not a vehicle) was blocked
        if (!(target instanceof Player) && !isVehicle(target)) {
            adv.grant(attacker, "no_violence");
        }
    }

    private Player resolvePlayerAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean isVehicle(Entity entity) {
        return entity instanceof Boat || entity instanceof Minecart;
    }
}
