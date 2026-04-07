package de.fairplay.listeners;

import de.fairplay.advancements.AdvancementManager;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

/**
 * Tracks ownership of boats and minecarts.
 * The player who places a vehicle becomes its owner; the entry is removed when
 * the vehicle is destroyed. Ownership is used by {@link CombatListener} to allow
 * players to break only their own vehicles.
 */
public class VehicleListener implements Listener {

    private final OwnershipStorage storage;
    private final AdvancementManager adv;

    public VehicleListener(OwnershipStorage storage, AdvancementManager adv) {
        this.storage = storage;
        this.adv = adv;
    }

    /** Registers ownership when a player places a boat or minecart. */
    @EventHandler
    public void onEntityPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Boat || entity instanceof Minecart) {
            storage.setEntityOwner(entity.getUniqueId(), event.getPlayer().getUniqueId());
            adv.grant(event.getPlayer(), "own_fleet");
        }
    }

    /** Removes the ownership entry when a vehicle is destroyed. */
    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        storage.removeEntityOwner(event.getVehicle().getUniqueId());
    }
}
