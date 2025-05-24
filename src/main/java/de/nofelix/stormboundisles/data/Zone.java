package de.nofelix.stormboundisles.data;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Represents a territorial zone in the game world defined by a 2D polygon.
 * 
 * Zones represent island territories and extend infinitely in the Y direction
 * (from bedrock to sky limit). They provide efficient horizontal containment
 * checking using ray-casting algorithms for arbitrarily-shaped polygons.
 * 
 * Example usage:
 * ```java
 * // Create a rectangular island territory
 * Zone territory = Zone.createRectangle(
 *     new BlockPos(0, 64, 0), 
 *     new BlockPos(100, 64, 100)
 * );
 * 
 * // Create a custom polygon territory
 * List<BlockPos> vertices = List.of(
 *     new BlockPos(0, 64, 0),
 *     new BlockPos(50, 64, 25),
 *     new BlockPos(25, 64, 75)
 * );
 * Zone territory = new Zone(vertices);
 * 
 * // Check if player is in territory (Y-coordinate ignored)
 * boolean inTerritory = zone.contains(playerPos);
 * ```
 */
public final class Zone {
    
    // Constants
    private static final double EDGE_TOLERANCE = 0.01;
    private static final double BLOCK_CENTER_OFFSET = 0.5;
    private static final int MIN_POLYGON_VERTICES = 3;
    private static final int RECTANGLE_VERTICES = 4;
    
    // Core properties (immutable)
    @NotNull
    private final List<BlockPos> points;

    /**
     * Constructs a new Zone from a list of vertices.
     * 
     * The zone represents a 2D territorial boundary that extends infinitely
     * in the Y direction. Only X and Z coordinates are used for containment
     * checking - Y coordinates in the vertices are ignored.
     *
     * @param points The list of BlockPos points defining the zone's polygon vertices
     * @throws IllegalArgumentException if points is null, empty, or contains fewer than 3 vertices
     */
    public Zone(@NotNull List<BlockPos> points) {
        validatePolygonPoints(points);
        this.points = List.copyOf(points); // Immutable defensive copy
    }

    // Static factory methods
    
    /**
     * Creates a rectangular zone from two corner points.
     * 
     * The method automatically determines the proper orientation regardless
     * of which corners are provided, creating a properly aligned rectangle.
     * The Y coordinates are ignored for territory purposes.
     *
     * @param corner1 The first corner position
     * @param corner2 The second corner position
     * @return A new Zone representing the rectangle territory
     * @throws IllegalArgumentException if either corner is null
     */
    @NotNull
    public static Zone createRectangle(@NotNull BlockPos corner1, @NotNull BlockPos corner2) {
        validateCorners(corner1, corner2);

        // Calculate bounds (Y coordinate doesn't matter for territories)
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        int y = 64; // Arbitrary Y coordinate since it's ignored

        // Create vertices in clockwise order for consistency
        List<BlockPos> rectanglePoints = List.of(
            new BlockPos(minX, y, minZ), // Top-left
            new BlockPos(maxX, y, minZ), // Top-right
            new BlockPos(maxX, y, maxZ), // Bottom-right
            new BlockPos(minX, y, maxZ)  // Bottom-left
        );

        return new Zone(rectanglePoints);
    }

    // Getters
    
    /**
     * Gets an unmodifiable list of vertices defining this polygon.
     * Note: Y coordinates in vertices are not used for containment checking.
     *
     * @return The polygon vertices (never null or empty)
     */
    @NotNull
    public List<BlockPos> getPoints() {
        return points; // Already immutable from constructor
    }

    /**
     * Gets the number of vertices in this zone's polygon.
     *
     * @return The vertex count
     */
    public int getVertexCount() {
        return points.size();
    }

    // Territory containment checking methods
    
    /**
     * Checks if the given position is contained within this territorial zone.
     * 
     * This method only checks horizontal containment (X and Z coordinates).
     * The Y coordinate is completely ignored, meaning the zone extends
     * infinitely upward and downward, representing island territory boundaries.
     *
     * @param pos The position to check
     * @return true if the position is inside the territory, false otherwise
     * @throws IllegalArgumentException if pos is null
     */
    public boolean contains(@NotNull BlockPos pos) {
        validatePosition(pos);
        
        // Check if point is exactly on polygon edge
        if (isOnPolygonEdge(pos)) {
            return true;
        }

        return performRayCasting(pos);
    }

    // State check methods
    
    /**
     * Checks if this zone represents a rectangle.
     *
     * @return true if the zone has exactly 4 vertices, false otherwise
     */
    public boolean isRectangle() {
        return points.size() == RECTANGLE_VERTICES;
    }

    // Private helper methods
    
    /**
     * Validates that polygon points meet the minimum requirements.
     */
    private static void validatePolygonPoints(@Nullable List<BlockPos> points) {
        if (points == null) {
            throw new IllegalArgumentException("Points list cannot be null");
        }
        if (points.size() < MIN_POLYGON_VERTICES) {
            throw new IllegalArgumentException(
                "A polygon zone requires at least %d points, got %d".formatted(MIN_POLYGON_VERTICES, points.size())
            );
        }
        if (points.contains(null)) {
            throw new IllegalArgumentException("Points list cannot contain null positions");
        }
    }

    /**
     * Validates corner positions for rectangle creation.
     */
    private static void validateCorners(@Nullable BlockPos corner1, @Nullable BlockPos corner2) {
        if (corner1 == null || corner2 == null) {
            throw new IllegalArgumentException("Corner positions cannot be null");
        }
    }

    /**
     * Validates a position parameter.
     */
    private static void validatePosition(@Nullable BlockPos pos) {
        if (pos == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
    }

    /**
     * Gets the X coordinate of a block's center.
     */
    private static double getCenterX(@NotNull BlockPos pos) {
        return pos.getX() + BLOCK_CENTER_OFFSET;
    }

    /**
     * Gets the Z coordinate of a block's center.
     */
    private static double getCenterZ(@NotNull BlockPos pos) {
        return pos.getZ() + BLOCK_CENTER_OFFSET;
    }

    /**
     * Checks if a position lies exactly on any edge of the polygon.
     * Only uses X and Z coordinates - Y is ignored.
     */
    private boolean isOnPolygonEdge(@NotNull BlockPos pos) {
        double x = getCenterX(pos);
        double z = getCenterZ(pos);
        int n = points.size();

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n; // Next vertex (wrapping to 0 for last vertex)
            
            if (isPointOnLineSegment(x, z, points.get(i), points.get(j))) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Checks if a point lies on a line segment between two vertices.
     * Only uses X and Z coordinates - Y is ignored.
     */
    private boolean isPointOnLineSegment(double x, double z, @NotNull BlockPos vertex1, @NotNull BlockPos vertex2) {
        double x1 = getCenterX(vertex1);
        double z1 = getCenterZ(vertex1);
        double x2 = getCenterX(vertex2);
        double z2 = getCenterZ(vertex2);
        
        return calculateDistanceToLineSegmentSquared(x, z, x1, z1, x2, z2) < EDGE_TOLERANCE;
    }

    /**
     * Calculates the squared distance from a point to a line segment.
     * Uses only X and Z coordinates.
     */
    private static double calculateDistanceToLineSegmentSquared(
            double pointX, double pointZ, 
            double lineX1, double lineZ1, 
            double lineX2, double lineZ2) {
        
        double lineLength = (lineX2 - lineX1) * (lineX2 - lineX1) + (lineZ2 - lineZ1) * (lineZ2 - lineZ1);
        
        if (lineLength == 0.0) {
            // Line segment is actually a point
            return (pointX - lineX1) * (pointX - lineX1) + (pointZ - lineZ1) * (pointZ - lineZ1);
        }

        // Calculate projection parameter
        double t = ((pointX - lineX1) * (lineX2 - lineX1) + (pointZ - lineZ1) * (lineZ2 - lineZ1)) / lineLength;
        t = Math.clamp(t, 0.0, 1.0); // Clamp to line segment

        // Calculate projection point
        double projectionX = lineX1 + t * (lineX2 - lineX1);
        double projectionZ = lineZ1 + t * (lineZ2 - lineZ1);

        // Return squared distance to projection
        return (pointX - projectionX) * (pointX - projectionX) + (pointZ - projectionZ) * (pointZ - projectionZ);
    }

    /**
     * Performs ray-casting algorithm to determine if a point is inside the polygon.
     * Only uses X and Z coordinates - Y is ignored.
     */
    private boolean performRayCasting(@NotNull BlockPos pos) {
        boolean inside = false;
        double x = getCenterX(pos);
        double z = getCenterZ(pos);
        int n = points.size();

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n; // Next vertex (wrapping)
            
            double xi = getCenterX(points.get(i));
            double zi = getCenterZ(points.get(i));
            double xj = getCenterX(points.get(j));
            double zj = getCenterZ(points.get(j));

            // Check if ray intersects with polygon edge
            if (((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }

        return inside;
    }

    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof Zone other && Objects.equals(points, other.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(points);
    }

    @Override
    public String toString() {
        return "Zone{vertices=%d, bounds=(%d,%d) to (%d,%d)}".formatted(
                points.size(),
                getMinX(), getMinZ(),
                getMaxX(), getMaxZ()
        );
    }

    // Additional utility methods for 2D bounds
    
    /**
     * Gets the minimum X coordinate among all vertices.
     */
    private int getMinX() {
        return points.stream().mapToInt(BlockPos::getX).min().orElse(0);
    }

    /**
     * Gets the maximum X coordinate among all vertices.
     */
    private int getMaxX() {
        return points.stream().mapToInt(BlockPos::getX).max().orElse(0);
    }

    /**
     * Gets the minimum Z coordinate among all vertices.
     */
    private int getMinZ() {
        return points.stream().mapToInt(BlockPos::getZ).min().orElse(0);
    }

    /**
     * Gets the maximum Z coordinate among all vertices.
     */
    private int getMaxZ() {
        return points.stream().mapToInt(BlockPos::getZ).max().orElse(0);
    }
}