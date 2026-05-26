package de.fairplay.storage;

import de.fairplay.FairPlayPlugin;
import org.bukkit.block.Block;

import java.io.File;
import java.sql.*;
import java.util.UUID;

/**
 * Persists block and entity ownership in a local SQLite database
 * ({@code plugins/FairPlay/ownership.db}).
 *
 * <p>Two tables are used:
 * <ul>
 *   <li>{@code block_ownership} — keyed by world name + x/y/z coordinates</li>
 *   <li>{@code entity_ownership} — keyed by entity UUID</li>
 * </ul>
 * All methods are synchronous and must be called from the server main thread.
 */
public class OwnershipStorage {

    private final FairPlayPlugin plugin;
    private Connection connection;

    /**
     * Constructs a new OwnershipStorage with the given plugin instance.
     *
     * @param plugin the owning {@link FairPlayPlugin}, used for data folder access and logging
     */
    public OwnershipStorage(FairPlayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the SQLite connection and creates the tables if they do not exist yet.
     * Must be called once during plugin enable before any other method.
     */
    public void initialize() {
        try {
            plugin.getDataFolder().mkdirs();
            File dbFile = new File(plugin.getDataFolder(), "ownership.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS block_ownership (
                        world TEXT NOT NULL,
                        x     INTEGER NOT NULL,
                        y     INTEGER NOT NULL,
                        z     INTEGER NOT NULL,
                        owner TEXT NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS entity_ownership (
                        entity_uuid TEXT PRIMARY KEY,
                        owner       TEXT NOT NULL
                    )
                    """);
                // Tracks who last fed a turtle (for egg-ownership cycle).
                // Set on EntityEnterLoveModeEvent, read on egg-laying, cleared on death.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS entity_fedby (
                        entity_uuid TEXT PRIMARY KEY,
                        fed_by      TEXT NOT NULL
                    )
                    """);
                // Tracks who is responsible for a turtle-egg block (for hatchling ownership).
                // Set when the egg is laid, transferred to baby on hatch, then removed.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS block_fedby (
                        world  TEXT    NOT NULL,
                        x      INTEGER NOT NULL,
                        y      INTEGER NOT NULL,
                        z      INTEGER NOT NULL,
                        fed_by TEXT    NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database initialization failed: " + e.getMessage());
        }
    }

    // ── Block Ownership ──────────────────────────────────────────────────────

    /**
     * Stores or replaces the owner of a block.
     *
     * @param block the block whose ownership is set
     * @param owner UUID of the owning player
     */
    public void setBlockOwner(Block block, UUID owner) {
        setBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), owner);
    }

    /**
     * Stores or replaces the owner of a block identified by world name and coordinates.
     * Use this overload when a {@link Block} reference is not available (e.g. from a
     * {@link org.bukkit.block.BlockState} list).
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @param owner UUID of the owning player
     */
    public void setBlockOwner(String world, int x, int y, int z, UUID owner) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO block_ownership (world, x, y, z, owner) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            stmt.setString(5, owner.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("setBlockOwner failed: " + e.getMessage());
        }
    }

    /**
     * Returns the owner of the given block, or {@code null} if unowned.
     *
     * @param block the block to look up
     * @return owner UUID, or {@code null}
     */
    public UUID getBlockOwner(Block block) {
        return getBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Returns the owner of the block at the given coordinates, or {@code null} if unowned.
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @return owner UUID, or {@code null}
     */
    public UUID getBlockOwner(String world, int x, int y, int z) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT owner FROM block_ownership WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("owner"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getBlockOwner failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Removes the ownership entry for a block. No-op if the block was not owned.
     *
     * @param block the block whose ownership is removed
     */
    public void removeBlockOwner(Block block) {
        removeBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Removes the ownership entry for a block identified by coordinates.
     * No-op if the block was not owned.
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     */
    public void removeBlockOwner(String world, int x, int y, int z) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM block_ownership WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("removeBlockOwner failed: " + e.getMessage());
        }
    }

    // ── Entity Ownership ─────────────────────────────────────────────────────

    /**
     * Stores or replaces the owner of an entity (vehicle, bred or tamed animal).
     *
     * @param entityUUID UUID of the entity
     * @param owner      UUID of the owning player
     */
    public void setEntityOwner(UUID entityUUID, UUID owner) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO entity_ownership (entity_uuid, owner) VALUES (?, ?)")) {
            stmt.setString(1, entityUUID.toString());
            stmt.setString(2, owner.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("setEntityOwner failed: " + e.getMessage());
        }
    }

    /**
     * Returns the owner of an entity, or {@code null} if unowned (wild / not tracked).
     *
     * @param entityUUID UUID of the entity
     * @return owner UUID, or {@code null}
     */
    public UUID getEntityOwner(UUID entityUUID) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT owner FROM entity_ownership WHERE entity_uuid = ?")) {
            stmt.setString(1, entityUUID.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("owner"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getEntityOwner failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Removes the ownership entry for an entity. Typically called when the entity dies.
     * No-op if the entity was not tracked.
     *
     * @param entityUUID UUID of the entity
     */
    public void removeEntityOwner(UUID entityUUID) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM entity_ownership WHERE entity_uuid = ?")) {
            stmt.setString(1, entityUUID.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("removeEntityOwner failed: " + e.getMessage());
        }
    }

    // ── Entity FedBy ─────────────────────────────────────────────────────────

    /**
     * Stores or replaces the "fed by" entry for an entity (used for the turtle breeding cycle).
     * Records which player fed the entity (e.g. gave seagrass to a turtle).
     *
     * @param entityUUID UUID of the entity
     * @param fedBy      UUID of the player who fed it
     */
    public void setEntityFedBy(UUID entityUUID, UUID fedBy) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO entity_fedby (entity_uuid, fed_by) VALUES (?, ?)")) {
            stmt.setString(1, entityUUID.toString());
            stmt.setString(2, fedBy.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("setEntityFedBy failed: " + e.getMessage());
        }
    }

    /**
     * Returns the UUID of the player who last fed the entity, or {@code null} if none recorded.
     *
     * @param entityUUID UUID of the entity
     * @return feeder UUID, or {@code null}
     */
    public UUID getEntityFedBy(UUID entityUUID) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT fed_by FROM entity_fedby WHERE entity_uuid = ?")) {
            stmt.setString(1, entityUUID.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("fed_by"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getEntityFedBy failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Removes the "fed by" entry for an entity.
     * No-op if no entry exists.
     *
     * @param entityUUID UUID of the entity
     */
    public void removeEntityFedBy(UUID entityUUID) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM entity_fedby WHERE entity_uuid = ?")) {
            stmt.setString(1, entityUUID.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("removeEntityFedBy failed: " + e.getMessage());
        }
    }

    // ── Block FedBy ───────────────────────────────────────────────────────────

    /**
     * Stores or replaces the "fed by" entry for a block position (used for turtle egg blocks).
     * Records which player is responsible for the egg — used to assign ownership to hatchlings.
     *
     * @param block the block (turtle egg) whose fed-by is set
     * @param fedBy UUID of the responsible player
     */
    public void setBlockFedBy(Block block, UUID fedBy) {
        setBlockFedBy(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), fedBy);
    }

    /**
     * Stores or replaces the "fed by" entry for a block position identified by world and coords.
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @param fedBy UUID of the responsible player
     */
    public void setBlockFedBy(String world, int x, int y, int z, UUID fedBy) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO block_fedby (world, x, y, z, fed_by) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            stmt.setString(5, fedBy.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("setBlockFedBy failed: " + e.getMessage());
        }
    }

    /**
     * Returns the "fed by" UUID for a block, or {@code null} if none recorded.
     *
     * @param block the block to look up
     * @return feeder UUID, or {@code null}
     */
    public UUID getBlockFedBy(Block block) {
        return getBlockFedBy(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Returns the "fed by" UUID for a block at the given coordinates, or {@code null} if none.
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @return feeder UUID, or {@code null}
     */
    public UUID getBlockFedBy(String world, int x, int y, int z) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT fed_by FROM block_fedby WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("fed_by"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getBlockFedBy failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Removes the "fed by" entry for a block. No-op if none exists.
     *
     * @param block the block whose fed-by entry is removed
     */
    public void removeBlockFedBy(Block block) {
        removeBlockFedBy(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Removes the "fed by" entry for a block at the given coordinates. No-op if none exists.
     *
     * @param world world name
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     */
    public void removeBlockFedBy(String world, int x, int y, int z) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM block_fedby WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, world);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("removeBlockFedBy failed: " + e.getMessage());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Closes the SQLite connection. Called during plugin disable. */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close database connection: " + e.getMessage());
        }
    }
}
