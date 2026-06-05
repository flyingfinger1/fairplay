package de.fairplay.listeners;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pauses world simulation when no players are online and resumes it when
 * the first player joins.
 *
 * <p>Controlled by the {@code pause-when-empty} config key. When enabled,
 * the following game rules are set on the last player leaving and restored
 * to their running defaults when a player connects again:
 * <ul>
 *   <li>{@code doDaylightCycle} → {@code false} (running: {@code true})</li>
 *   <li>{@code doWeatherCycle}  → {@code false} (running: {@code true})</li>
 *   <li>{@code doMobSpawning}   → {@code false} (running: {@code true})</li>
 *   <li>{@code randomTickSpeed} → {@code 0}     (running: {@code 3})</li>
 * </ul>
 *
 * <p>The running values are always the Minecraft defaults — we do not try to
 * restore admin-set overrides. This avoids a class of bugs where a previous
 * paused session (restart-while-empty, plugin reload, double-pause) leaves
 * the "saved" value already in a paused state, causing resume to restore to
 * {@code false} instead of {@code true}.
 *
 * <p>On server startup with no players online the simulation is paused
 * immediately, protecting against restart-while-empty scenarios.
 */
public class ServerPauseListener implements Listener {

    private final JavaPlugin plugin;
    private boolean isPaused = false;

    /**
     * Constructs a new ServerPauseListener.
     *
     * @param plugin the owning plugin, used for server access and logging
     */
    public ServerPauseListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Synchronises the pause state with the current online player count.
     * Always resets gamerules to running defaults first so that stale paused
     * values from a previous session do not survive a restart.
     * Must be called once from {@code onEnable()} after the listener is registered.
     */
    public void initializeState() {
        // Always restore running defaults first — ensures a clean state even if
        // the server was shut down while paused (gamerules would still be false).
        for (World world : plugin.getServer().getWorlds()) {
            applyRunning(world);
        }
        isPaused = false;

        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            pause();
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /** Pauses the simulation when the last player leaves. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // size() == 1 during the event: this player has not been removed yet.
        // Guard against double-pause so we never call pauseWorld while already
        // paused (which would re-save the already-false gamerule values).
        if (!isPaused && plugin.getServer().getOnlinePlayers().size() == 1) {
            pause();
        }
    }

    /** Resumes the simulation when the first player joins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (isPaused) {
            resume();
        }
    }

    /** Applies the paused gamerules to any world that loads while paused. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (isPaused) {
            applyPaused(event.getWorld());
        }
    }

    // ── Pause / resume ────────────────────────────────────────────────────────

    private void pause() {
        isPaused = true;
        for (World world : plugin.getServer().getWorlds()) {
            applyPaused(world);
        }
        plugin.getLogger().info("World simulation paused (no players online).");
    }

    private void resume() {
        isPaused = false;
        for (World world : plugin.getServer().getWorlds()) {
            applyRunning(world);
        }
        plugin.getLogger().info("World simulation resumed.");
    }

    // ── Gamerule helpers ──────────────────────────────────────────────────────

    private static void applyPaused(World world) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE,  false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,   false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
    }

    private static void applyRunning(World world) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE,  true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,   true);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 3);
    }
}
