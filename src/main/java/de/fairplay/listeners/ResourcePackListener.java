package de.fairplay.listeners;

import de.fairplay.ResourcePackServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends the FairPlay resource pack to each player on login.
 * The pack contains translations for advancement titles and descriptions.
 */
public class ResourcePackListener implements Listener {

    private static final Component PROMPT =
        Component.text("FairPlay Resource Pack (advancement translations)");

    private final ResourcePackServer packServer;
    private final boolean required;

    public ResourcePackListener(ResourcePackServer packServer, boolean required) {
        this.packServer = packServer;
        this.required   = required;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        //noinspection deprecation
        player.setResourcePack(packServer.getUrl(), packServer.getSha1Hex(), required, PROMPT);
    }
}
