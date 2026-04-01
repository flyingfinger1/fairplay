package de.fairplay.storage;

import de.fairplay.FairPlayPlugin;
import org.bukkit.block.Block;

import java.io.File;
import java.sql.*;
import java.util.UUID;

public class OwnershipStorage {

    private final FairPlayPlugin plugin;
    private Connection connection;

    public OwnershipStorage(FairPlayPlugin plugin) {
        this.plugin = plugin;
    }

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
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database initialization failed: " + e.getMessage());
        }
    }

    // ── Block Ownership ──────────────────────────────────────────────────────

    public void setBlockOwner(Block block, UUID owner) {
        setBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), owner);
    }

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

    public UUID getBlockOwner(Block block) {
        return getBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

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

    public void removeBlockOwner(Block block) {
        removeBlockOwner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

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

    public void removeEntityOwner(UUID entityUUID) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM entity_ownership WHERE entity_uuid = ?")) {
            stmt.setString(1, entityUUID.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("removeEntityOwner failed: " + e.getMessage());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
