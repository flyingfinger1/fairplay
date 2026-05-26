package de.fairplay.listeners;

import de.fairplay.storage.OwnershipStorage;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Prevents pistons from pushing or pulling blocks that are not explicitly owned
 * by the piston's owner, and keeps the ownership database consistent after a
 * successful piston move.
 *
 * <p>Each direction (extend / retract) is handled by two event handlers:
 * <ol>
 *   <li>NORMAL priority — ownership check; cancels the event if any block in the
 *       chain is unowned or owned by a different player.</li>
 *   <li>MONITOR priority, ignoreCancelled — DB migration; runs only when the
 *       event was not cancelled, so the entries always reflect the actual world
 *       state.</li>
 * </ol>
 *
 * <p>Unowned blocks (natural terrain) are treated the same as foreign blocks:
 * a piston may only move blocks it explicitly owns.
 */
public class PistonListener implements Listener {

    private final OwnershipStorage storage;
    private final boolean teamMode;

    /**
     * Constructs a new PistonListener.
     *
     * @param storage  the ownership storage shared across all listeners
     * @param teamMode {@code true} if team mode is active (ownership checks relaxed)
     */
    public PistonListener(OwnershipStorage storage, boolean teamMode) {
        this.storage = storage;
        this.teamMode = teamMode;
    }

    // ── Extend ────────────────────────────────────────────────────────────────

    /**
     * Cancels a piston extension if it would push a block owned by a player
     * other than the piston's owner.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (teamMode) return;
        if (!canMove(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /**
     * Migrates ownership DB entries to the new block positions after a
     * successful piston extension.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtendMigrate(BlockPistonExtendEvent event) {
        if (teamMode) return;
        migrateOwners(event.getBlocks(), event.getDirection());
    }

    // ── Retract ───────────────────────────────────────────────────────────────

    /**
     * Cancels a sticky-piston retraction if it would pull a block owned by a
     * player other than the piston's owner. Non-sticky retractions (empty block
     * list) are always allowed.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (teamMode) return;
        if (event.getBlocks().isEmpty()) return; // non-sticky piston – nothing to pull
        if (!canMove(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /**
     * Migrates ownership DB entries to the new block positions after a
     * successful sticky-piston retraction.
     *
     * @param event the event fired by the server
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetractMigrate(BlockPistonRetractEvent event) {
        if (teamMode) return;
        if (event.getBlocks().isEmpty()) return;
        // Blocks move opposite to the direction the piston is facing
        migrateOwners(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Checks whether every block in {@code blocks} is explicitly owned by the
     * same player who owns {@code piston}. Unowned blocks (natural terrain) are
     * treated like foreign blocks and will also block the move.
     *
     * @param piston the piston block initiating the move
     * @param blocks the blocks that would be pushed or pulled
     * @return {@code true} if the move is permitted; {@code false} to cancel
     */
    private boolean canMove(Block piston, List<Block> blocks) {
        UUID pistonOwner = storage.getBlockOwner(piston);
        for (Block b : blocks) {
            UUID blockOwner = storage.getBlockOwner(b);
            // Block must be explicitly owned by the piston owner.
            // Unowned blocks (natural terrain) are not movable either.
            if (blockOwner == null || !blockOwner.equals(pistonOwner)) return false;
        }
        return true;
    }

    /**
     * Moves ownership DB entries from each block's current position to
     * {@code currentPos.getRelative(moveDir)}.
     *
     * <p>Iteration runs from the far end of the block chain back toward the
     * piston so that no entry is overwritten before it has been read.
     *
     * @param blocks  the blocks being moved, in push-order (nearest first)
     * @param moveDir the direction in which those blocks travel
     */
    private void migrateOwners(List<Block> blocks, BlockFace moveDir) {
        List<Block> reversed = new ArrayList<>(blocks);
        Collections.reverse(reversed); // process far end first to avoid collisions

        for (Block b : reversed) {
            UUID owner = storage.getBlockOwner(b);
            storage.removeBlockOwner(b);
            if (owner != null) {
                storage.setBlockOwner(b.getRelative(moveDir), owner);
            }
        }
    }
}
