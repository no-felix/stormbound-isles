package de.nofelix.stormboundisles.command.util;

import de.nofelix.stormboundisles.data.Zone;
import de.nofelix.stormboundisles.init.Initialize;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages polygon building state for island zones in the Stormbound Isles mod.
 * <p>
 * This manager maintains player polygon building sessions, allowing players to
 * create
 * polygon or rectangle zones for islands through a multi-step process. Each
 * player can
 * have one active polygon building session at a time, identified by their UUID.
 */
public final class PolygonBuilderManager {

    /**
     * Private constructor to prevent instantiation.
     */
    private PolygonBuilderManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Represents a polygon zone being built by a player.
     * <p>
     * This class collects points added by the player and provides methods to create
     * either a rectangle (from two points) or a full polygon (from three or more
     * points).
     */
    public static class PolygonBuilder {
        /** The island ID this polygon is being built for */
        private final String islandId;

        /** The list of points (block positions) in the polygon */
        private final List<BlockPos> points = new ArrayList<>();

        /**
         * Creates a new polygon builder for the specified island.
         *
         * @param islandId The ID of the island this polygon is being created for
         */
        public PolygonBuilder(String islandId) {
            this.islandId = islandId;
        }

        /**
         * Gets the island ID this polygon is being built for.
         *
         * @return The island ID
         */
        public String getIslandId() {
            return islandId;
        }

        /**
         * Gets the current list of points in the polygon.
         *
         * @return An unmodifiable view of the points list
         */
        public List<BlockPos> getPoints() {
            return points;
        }

        /**
         * Adds a new point to the polygon.
         *
         * @param point The block position to add
         */
        public void addPoint(BlockPos point) {
            points.add(point);
        }

        /**
         * Gets the number of points currently in the polygon.
         *
         * @return The number of points
         */
        public int getPointCount() {
            return points.size();
        }

        /**
         * Creates a rectangular zone from two points.
         * <p>
         * Uses the first point in the current points list and the provided second point
         * as opposite corners of a rectangle.
         *
         * @param secondPoint The opposite corner of the rectangle
         * @return A new rectangular Zone
         * @throws IllegalStateException if no points have been added yet
         */
        public Zone createRectangle(BlockPos secondPoint) {
            if (points.isEmpty()) {
                throw new IllegalStateException("No points defined for rectangle");
            }

            BlockPos firstPoint = points.get(0);
            return Zone.createRectangle(firstPoint, secondPoint);
        }

        /**
         * Creates a polygon zone from all added points.
         * <p>
         * The polygon requires at least 3 points to be valid.
         *
         * @return A new Zone containing all added points
         * @throws IllegalStateException if fewer than 3 points have been added
         */
        public Zone createPolygon() {
            if (points.size() < 3) {
                throw new IllegalStateException("At least 3 points are required for a polygon");
            }

            return new Zone(points);
        }
    }

    /** Map of active polygon builders by player UUID */
    private static final Map<UUID, PolygonBuilder> polygonBuilders = new Object2ObjectOpenHashMap<>();

    /**
     * Initializes the polygon builder manager.
     * This method is automatically called during mod startup via the
     * annotation-based initialization system.
     */
    @Initialize(priority = 1500, description = "Initialize polygon builder manager")
    public static void initialize() {
        // This method is intentionally empty for now, but can be used to initialize any
        // state in the future
    }

    /**
     * Starts a new polygon building session for a player.
     * <p>
     * If the player already has an active session, it will be replaced.
     * 
     * @param playerId The UUID of the player
     * @param islandId The ID of the island to build the polygon for
     * @return The new polygon builder
     */
    public static PolygonBuilder startPolygon(UUID playerId, String islandId) {
        PolygonBuilder builder = new PolygonBuilder(islandId);
        polygonBuilders.put(playerId, builder);
        return builder;
    }

    /**
     * Gets the current polygon builder for a player.
     * 
     * @param playerId The UUID of the player
     * @return The polygon builder, or null if the player has no active session
     */
    public static PolygonBuilder getBuilder(UUID playerId) {
        return polygonBuilders.get(playerId);
    }

    /**
     * Removes and returns the polygon builder for a player.
     * <p>
     * This should be called when a player completes or cancels a polygon building
     * session.
     * 
     * @param playerId The UUID of the player
     * @return The removed polygon builder, or null if the player had no active
     *         session
     */
    public static PolygonBuilder removeBuilder(UUID playerId) {
        return polygonBuilders.remove(playerId);
    }
}