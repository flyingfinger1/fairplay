package de.fairplay.listeners;

import de.fairplay.ResourcePackServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends the FairPlay resource pack to each player on login.
 * The pack contains translations for advancement titles and descriptions.
 *
 * <p>Uses the 2-argument {@code Player#setResourcePack(url, hash)} for compatibility
 * with Paper 1.17, which does not yet have the required/prompt overloads.
 */
public class ResourcePackListener implements Listener {

    private final ResourcePackServer packServer;

    /**
     * Constructs a new ResourcePackListener with the given pack server.
     *
     * @param packServer the server providing the resource pack URL and SHA-1 hash
     */
    public ResourcePackListener(ResourcePackServer packServer) {
        this.packServer = packServer;
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.setResourcePack(packServer.getUrl(), packServer.getSha1Hex());
    }
}
