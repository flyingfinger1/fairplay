package de.fairplay.listeners;

import de.fairplay.ResourcePackServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.URI;
import java.util.UUID;

/**
 * Sends the FairPlay resource pack to each player on login.
 * The pack contains translations for advancement titles and descriptions.
 */
public class ResourcePackListener implements Listener {

    private static final Component PROMPT =
        Component.text("FairPlay Resource Pack (advancement translations)");

    // Stable UUID that identifies this pack on the client side across sessions.
    private static final UUID PACK_ID =
        UUID.fromString("a1b2c3d4-e5f6-4789-abcd-ef0123456789");

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

    /**
     * Sends the FairPlay resource pack to a player when they join.
     * Uses the Adventure {@link ResourcePackRequest} API (replaces the deprecated
     * {@code Player#setResourcePack} method).
     *
     * @param event the event fired by the server
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo()
            .id(PACK_ID)
            .uri(URI.create(packServer.getUrl()))
            .hash(packServer.getSha1Hex())
            .build();

        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
            .packs(info)
            .required(required)
            .prompt(PROMPT)
            .build();

        player.sendResourcePacks(request);
    }
}
