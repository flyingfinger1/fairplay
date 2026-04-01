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

public class VehicleListener implements Listener {

    private final OwnershipStorage storage;
    private final AdvancementManager adv;

    public VehicleListener(OwnershipStorage storage, AdvancementManager adv) {
        this.storage = storage;
        this.adv = adv;
    }

    @EventHandler
    public void onEntityPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Boat || entity instanceof Minecart) {
            storage.setEntityOwner(entity.getUniqueId(), event.getPlayer().getUniqueId());
            adv.grant(event.getPlayer(), "eigene_flotte");
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        storage.removeEntityOwner(event.getVehicle().getUniqueId());
    }
}
