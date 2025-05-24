package de.nofelix.stormboundisles.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an island in the game world with its properties and state.
 * 
 * Islands are uniquely identified by their ID and can be assigned to teams,
 * have custom spawn points, and belong to geographical zones.
 * 
 * Example usage:
 * ```java
 * Island island = new Island("island_01", IslandType.VOLCANO);
 * island.setTeamName("red");
 * island.setSpawnPoint(100, 64, 200);
 * ```
 */
public final class Island {
    
    // Constants for validation
    private static final int UNDEFINED_SPAWN_Y = -1;
    
    // Core properties (immutable)
    @NotNull
    private final String id;
    
    // Mutable properties
    @NotNull
    private IslandType type;
    @Nullable
    private Zone zone;
    @Nullable
    private String teamName;
    
    // Spawn point coordinates
    private int spawnX = 0;
    private int spawnY = UNDEFINED_SPAWN_Y;
    private int spawnZ = 0;

    /**
     * Constructs a new Island with the given ID and type.
     *
     * @param id   The unique identifier for the island
     * @param type The type of the island
     * @throws IllegalArgumentException if id is null/empty or type is null
     */
    public Island(@NotNull String id, @NotNull IslandType type) {
        validateId(id);
        validateType(type);
        
        this.id = id;
        this.type = type;
    }

    // Getters
    
    /**
     * Gets the island's unique identifier.
     *
     * @return The island ID (never null or empty)
     */
    @NotNull
    public String getId() {
        return id;
    }

    /**
     * Gets the island's type.
     *
     * @return The island type (never null)
     */
    @NotNull
    public IslandType getType() {
        return type;
    }

    /**
     * Gets the island's geographical zone.
     *
     * @return The island's zone, or null if not assigned
     */
    @Nullable
    public Zone getZone() {
        return zone;
    }

    /**
     * Gets the name of the team assigned to this island.
     *
     * @return The team name, or null if no team is assigned
     */
    @Nullable
    public String getTeamName() {
        return teamName;
    }

    /**
     * Gets the X-coordinate of the custom spawn point.
     *
     * @return The spawn X-coordinate
     */
    public int getSpawnX() {
        return spawnX;
    }

    /**
     * Gets the Y-coordinate of the custom spawn point.
     * A value of -1 indicates an undefined spawn point.
     *
     * @return The spawn Y-coordinate, or -1 if undefined
     */
    public int getSpawnY() {
        return spawnY;
    }

    /**
     * Gets the Z-coordinate of the custom spawn point.
     *
     * @return The spawn Z-coordinate
     */
    public int getSpawnZ() {
        return spawnZ;
    }

    // Setters with validation
    
    /**
     * Sets the island's type.
     *
     * @param type The new island type
     * @throws IllegalArgumentException if type is null
     */
    public void setType(@NotNull IslandType type) {
        validateType(type);
        this.type = type;
    }

    /**
     * Sets the island's geographical zone.
     *
     * @param zone The new geographical zone, or null to clear
     */
    public void setZone(@Nullable Zone zone) {
        this.zone = zone;
    }

    /**
     * Assigns a team to this island.
     *
     * @param teamName The name of the team to assign, or null to clear assignment
     */
    public void setTeamName(@Nullable String teamName) {
        this.teamName = (teamName != null && teamName.trim().isEmpty()) ? null : teamName;
    }

    /**
     * Sets the custom spawn point coordinates for this island.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate (use -1 to mark as undefined)
     * @param z The Z-coordinate
     */
    public void setSpawnPoint(int x, int y, int z) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
    }

    /**
     * Clears the custom spawn point, marking it as undefined.
     */
    public void clearSpawnPoint() {
        this.spawnX = 0;
        this.spawnY = UNDEFINED_SPAWN_Y;
        this.spawnZ = 0;
    }

    // State check methods
    
    /**
     * Checks if this island has a defined spawn point.
     *
     * @return true if a spawn point is defined (Y >= 0), false otherwise
     */
    public boolean hasSpawnPoint() {
        return spawnY >= 0;
    }

    /**
     * Checks if this island has a defined zone.
     *
     * @return true if a zone is assigned, false otherwise
     */
    public boolean hasZone() {
        return zone != null;
    }

    /**
     * Checks if this island has a team assigned to it.
     *
     * @return true if a team is assigned, false otherwise
     */
    public boolean hasTeam() {
        return teamName != null && !teamName.trim().isEmpty();
    }

    /**
     * Checks if this island is unassigned (no team).
     *
     * @return true if no team is assigned, false otherwise
     */
    public boolean isUnassigned() {
        return !hasTeam();
    }

    // Private validation methods
    
    private static void validateId(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Island ID cannot be null or empty");
        }
    }

    private static void validateType(@Nullable IslandType type) {
        if (type == null) {
            throw new IllegalArgumentException("Island type cannot be null");
        }
    }

    // Object methods
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof Island other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Island{id='%s', type=%s, zone=%s, team='%s', spawnPoint=%s}".formatted(
                id,
                type,
                zone != null ? zone : "none",
                teamName != null ? teamName : "unassigned",
                hasSpawnPoint() ? "(%d,%d,%d)".formatted(spawnX, spawnY, spawnZ) : "undefined"
        );
    }
}