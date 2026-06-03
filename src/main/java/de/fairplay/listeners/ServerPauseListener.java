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

import java.util.HashMap;
import java.util.Map;

/**
 * Pauses world simulation when no players are online and resumes it when
 * the first player joins.
 *
 * <p>Controlled by the {@code pause-when-empty} config key. When enabled,
 * the following game rules are frozen on the last player leaving and
 * restored when a player connects again:
 * <ul>
 *   <li>{@code doDaylightCycle} → {@code false}</li>
 *   <li>{@code doWeatherCycle}  → {@code false}</li>
 *   <li>{@code doMobSpawning}   → {@code false}</li>
 *   <li>{@code randomTickSpeed} → {@code 0}</li>
 * </ul>
 *
 * <p>The values in effect <em>before</em> the pause are saved per world and
 * restored exactly on resume, so any admin overrides (e.g. a permanently
 * disabled mob spawning) survive the pause/resume cycle.
 *
 * <p>On server startup with no players online the simulation is also paused
 * immediately, protecting against restart-while-empty scenarios.
 */
public class ServerPauseListener implements Listener {

    private final JavaPlugin plugin;
    private boolean isPaused = false;

    /** Saved game-rule values per world, keyed by world name. */
    private final Map<String, SavedRules> savedRules = new HashMap<>();

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
     * Must be called once from {@code onEnable()} after the listener is registered,
     * so that a restart-while-empty scenario is handled correctly.
     */
    public void initializeState() {
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            pause();
        } else {
            resume(false); // players are online → ensure we are running
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /** Pauses the simulation when the last player leaves. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // size() == 1 during the event: this player has not been removed yet
        if (plugin.getServer().getOnlinePlayers().size() == 1) {
            pause();
        }
    }

    /** Resumes the simulation when the first player joins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (isPaused) {
            resume(true);
        }
    }

    /** Applies the current pause state to any world that loads while paused. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (isPaused) {
            pauseWorld(event.getWorld());
        }
    }

    // ── Pause / resume ────────────────────────────────────────────────────────

    private void pause() {
        isPaused = true;
        for (World world : plugin.getServer().getWorlds()) {
            pauseWorld(world);
        }
        plugin.getLogger().info("World simulation paused (no players online).");
    }

    private void pauseWorld(World world) {
        // Save current values before overwriting them
        savedRules.put(world.getName(), new SavedRules(
                world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE),
                world.getGameRuleValue(GameRule.DO_WEATHER_CYCLE),
                world.getGameRuleValue(GameRule.DO_MOB_SPAWNING),
                world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED)
        ));
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE,  false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,   false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
    }

    /**
     * @param log whether to log the resume message (suppressed during startup
     *            when no actual pause occurred beforehand)
     */
    private void resume(boolean log) {
        isPaused = false;
        for (World world : plugin.getServer().getWorlds()) {
            resumeWorld(world);
        }
        if (log) {
            plugin.getLogger().info("World simulation resumed.");
        }
    }

    private void resumeWorld(World world) {
        SavedRules saved = savedRules.remove(world.getName());
        if (saved == null) {
            // No prior pause recorded for this world — use safe Minecraft defaults
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE,  true);
            world.setGameRule(GameRule.DO_MOB_SPAWNING,   true);
            world.setGameRule(GameRule.RANDOM_TICK_SPEED, 3);
            return;
        }
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, saved.daylightCycle);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE,  saved.weatherCycle);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,   saved.mobSpawning);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, saved.tickSpeed);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Snapshot of game-rule values taken just before a pause. */
    private record SavedRules(
            Boolean daylightCycle,
            Boolean weatherCycle,
            Boolean mobSpawning,
            Integer tickSpeed
    ) {}
}
