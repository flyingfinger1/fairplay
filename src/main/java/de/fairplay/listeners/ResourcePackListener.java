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
 *
 * <p>Uses the legacy {@code Player#setResourcePack} API for compatibility with
 * Paper 1.19–1.20.4 (Adventure {@code ResourcePackInfo} was not yet available).
 */
public class ResourcePackListener implements Listener {

    private static final Component PROMPT =
        Component.text("FairPlay Resource Pack (advancement translations)");

    private final ResourcePackServer packServer;
    private final boolean required;

    /**
     * Constructs a new ResourcePackListener with the given pack server and requirement flag.
     *
     * @param packServer the server providing the resource pack URL and SHA-1 hash
     * @param required   {@code true} if the client must accept the pack to join
     */
    public ResourcePackListener(ResourcePackServer packServer, boolean required) {
        this.packServer = packServer;
        this.required   = required;
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.setResourcePack(
            packServer.getUrl(),
            packServer.getSha1Hex(),
            required,
            PROMPT
        );
    }
}
