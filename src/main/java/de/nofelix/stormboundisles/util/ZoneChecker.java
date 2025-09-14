package de.nofelix.stormboundisles.util;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Zone;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimized zone containment checker with spatial caching.
 * Provides fast zone checking with automatic cache invalidation.
 *
 * Features:
 * - Spatial caching based on player movement
 * - Automatic cache cleanup for offline players
 * - Thread-safe concurrent access
 * - Configurable cache distance threshold
 */
public final class ZoneChecker {

    // Cache structure: Island ID -> Player UUID -> Containment result
    private static final Map<String, Map<UUID, Boolean>> islandPlayerCache = new ConcurrentHashMap<>();

    // Player position cache for movement detection
    private static final Map<UUID, BlockPos> playerPositionCache = new ConcurrentHashMap<>();

    // Configuration
    private static final int POSITION_CACHE_DISTANCE = 16; // blocks
    private static final long CLEANUP_INTERVAL_TICKS = 1200; // 1 minute
    private static long lastCleanupTime = 0;

    private ZoneChecker() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks if a player is within an island's zone using spatial caching.
     *
     * @param islandId The island identifier
     * @param zone     The zone to check against
     * @param player   The player to check
     * @return true if the player is within the zone
     */
    public static boolean isPlayerInZone(String islandId, Zone zone, ServerPlayerEntity player) {
        if (zone == null) {
            return false;
        }

        UUID playerId = player.getUuid();
        BlockPos currentPos = player.getBlockPos();

        // Get or create cache for this island
        Map<UUID, Boolean> playerCache = islandPlayerCache.computeIfAbsent(islandId, k -> new ConcurrentHashMap<>());

        // Check if we need to revalidate this player's position
        boolean needsCheck = true;
        BlockPos cachedPos = playerPositionCache.get(playerId);

        if (cachedPos != null) {
            // Only recheck if player moved more than cache distance
            double distance = Math.sqrt(
                    Math.pow((double) currentPos.getX() - cachedPos.getX(), 2) +
                            Math.pow((double) currentPos.getY() - cachedPos.getY(), 2) +
                            Math.pow((double) currentPos.getZ() - cachedPos.getZ(), 2));
            needsCheck = distance > POSITION_CACHE_DISTANCE;
        }

        Boolean cachedResult = null;
        if (!needsCheck) {
            cachedResult = playerCache.get(playerId);
        }

        boolean isInZone;
        if (cachedResult != null) {
            isInZone = cachedResult;
        } else {
            // Perform expensive check
            isInZone = zone.contains(currentPos);
            playerCache.put(playerId, isInZone);
            playerPositionCache.put(playerId, currentPos);
        }

        return isInZone;
    }

    /**
     * Gets all players within an island's zone using spatial caching.
     *
     * @param islandId   The island identifier
     * @param zone       The zone to check against
     * @param allPlayers List of all online players
     * @return List of players within the zone
     */
    public static List<ServerPlayerEntity> getPlayersInZone(String islandId, Zone zone,
            List<ServerPlayerEntity> allPlayers) {
        if (zone == null) {
            return Collections.emptyList();
        }

        List<ServerPlayerEntity> playersInZone = new ArrayList<>();

        for (ServerPlayerEntity player : allPlayers) {
            if (isPlayerInZone(islandId, zone, player)) {
                playersInZone.add(player);
            }
        }

        return playersInZone;
    }

    /**
     * Finds which island a player is currently in.
     *
     * @param player  The player to check
     * @param islands Map of all islands
     * @return The island ID the player is in, or null if none
     */
    public static String findPlayerIsland(ServerPlayerEntity player, Map<String, Island> islands) {
        for (Map.Entry<String, Island> entry : islands.entrySet()) {
            String islandId = entry.getKey();
            Island island = entry.getValue();

            if (island.getZone() != null && isPlayerInZone(islandId, island.getZone(), player)) {
                return islandId;
            }
        }
        return null;
    }

    /**
     * Performs periodic cleanup of the spatial cache.
     * Should be called regularly to prevent memory leaks.
     *
     * @param onlinePlayers Current list of online players
     */
    public static void cleanupCache(List<ServerPlayerEntity> onlinePlayers) {
        long currentTime = System.currentTimeMillis();

        // Only cleanup periodically
        if (currentTime - lastCleanupTime < CLEANUP_INTERVAL_TICKS * 50) { // 50ms per tick
            return;
        }

        lastCleanupTime = currentTime;

        // Get set of online player UUIDs
        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayerEntity player : onlinePlayers) {
            onlinePlayerIds.add(player.getUuid());
        }

        // Clean up position cache
        playerPositionCache.keySet().removeIf(uuid -> !onlinePlayerIds.contains(uuid));

        // Clean up island player cache
        for (Map<UUID, Boolean> playerCache : islandPlayerCache.values()) {
            playerCache.keySet().removeIf(uuid -> !onlinePlayerIds.contains(uuid));
        }

        // Remove empty island caches
        islandPlayerCache.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        StormboundIslesMod.LOGGER.debug("ZoneChecker cache cleaned up. Active players: {}, Cached islands: {}",
                onlinePlayerIds.size(), islandPlayerCache.size());
    }

    /**
     * Checks if a BlockPos is within a zone (no caching for arbitrary positions).
     *
     * @param zone The zone to check against
     * @param pos  The position to check
     * @return true if the position is within the zone
     */
    public static boolean isPositionInZone(Zone zone, BlockPos pos) {
        if (zone == null || pos == null) {
            return false;
        }
        return zone.contains(pos);
    }

    /**
     * Clears all cached data. Useful for testing or when islands change.
     */
    public static void clearCache() {
        islandPlayerCache.clear();
        playerPositionCache.clear();
        StormboundIslesMod.LOGGER.debug("ZoneChecker cache cleared");
    }

    /**
     * Gets cache statistics for monitoring.
     *
     * @return Map containing cache statistics
     */
    public static Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedIslands", islandPlayerCache.size());
        stats.put("cachedPlayers", playerPositionCache.size());
        stats.put("cacheDistance", POSITION_CACHE_DISTANCE);
        stats.put("cleanupIntervalTicks", CLEANUP_INTERVAL_TICKS);

        int totalCachedResults = 0;
        for (Map<UUID, Boolean> playerCache : islandPlayerCache.values()) {
            totalCachedResults += playerCache.size();
        }
        stats.put("totalCachedResults", totalCachedResults);

        return stats;
    }
}