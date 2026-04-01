package de.fairplay;

import de.fairplay.advancements.AdvancementManager;
import de.fairplay.listeners.AdvancementListener;
import de.fairplay.listeners.BlockOwnershipListener;
import de.fairplay.listeners.CauldronListener;
import de.fairplay.listeners.CombatListener;
import de.fairplay.listeners.GrowthListener;
import de.fairplay.listeners.LootListener;
import de.fairplay.listeners.ResourcePackListener;
import de.fairplay.listeners.VehicleListener;
import de.fairplay.storage.OwnershipStorage;
import org.bukkit.GameRule;
import org.bukkit.plugin.java.JavaPlugin;

public class FairPlayPlugin extends JavaPlugin {

    private OwnershipStorage storage;
    private AdvancementManager advManager;
    private ResourcePackServer resourcePackServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        storage = new OwnershipStorage(this);
        storage.initialize();

        advManager = new AdvancementManager(this);
        boolean dataPackChanged = advManager.install();

        // Data Pack hat sich geändert → reloadData() damit die neuen Dateien
        // noch in dieser Server-Session wirksam werden (1 Tick warten bis
        // alle Plugins vollständig geladen sind).
        if (dataPackChanged) {
            getServer().getScheduler().runTaskLater(this,
                () -> getServer().reloadData(), 1L);
        }

        // Resource Pack starten (Übersetzungen für Advancements)
        resourcePackServer = new ResourcePackServer();
        try {
            resourcePackServer.start(this);
            boolean required = getConfig().getBoolean("resource-pack-required", false);
            getServer().getPluginManager().registerEvents(
                new ResourcePackListener(resourcePackServer, required), this);
        } catch (Exception e) {
            getLogger().warning("Resource Pack Server konnte nicht gestartet werden: " + e.getMessage());
        }

        getServer().getPluginManager().registerEvents(new BlockOwnershipListener(storage, advManager), this);
        getServer().getPluginManager().registerEvents(new CauldronListener(storage, this, advManager), this);
        getServer().getPluginManager().registerEvents(new GrowthListener(this, storage, advManager), this);
        getServer().getPluginManager().registerEvents(new CombatListener(storage, advManager), this);
        getServer().getPluginManager().registerEvents(new LootListener(advManager), this);
        getServer().getPluginManager().registerEvents(new VehicleListener(storage, advManager), this);
        getServer().getPluginManager().registerEvents(new AdvancementListener(advManager), this);

        // Vanilla-Advancement-Announcements global deaktivieren
        getServer().getWorlds().forEach(w -> w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false));

        getLogger().info("FairPlay aktiviert.");
    }

    @Override
    public void onDisable() {
        if (resourcePackServer != null) {
            resourcePackServer.stop();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("FairPlay deaktiviert.");
    }

    public OwnershipStorage getStorage() {
        return storage;
    }

    public AdvancementManager getAdvManager() {
        return advManager;
    }
}
