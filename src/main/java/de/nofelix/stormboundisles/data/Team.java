package de.nofelix.stormboundisles.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a team in the game with members, island assignment, and scoring.
 * 
 * Teams are uniquely identified by their name and can have players assigned,
 * be assigned to islands, and accumulate points during gameplay.
 * 
 * Example usage:
 * ```java
 * Team redTeam = new Team("red");
 * redTeam.addMember(playerUuid);
 * redTeam.setIslandId("island_01");
 * redTeam.addPoints(100);
 * ```
 */
public final class Team {

    // Constants
    private static final String TEAM_NAME_FIELD = "Team name";
    private static final String PLAYER_UUID_FIELD = "Player UUID";

    // Core properties (immutable)
    @NotNull
    private final String name;

    // Thread-safe member management
    @NotNull
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();

    // Mutable properties
    @Nullable
    private String islandId;
    private int points = 0;

    /**
     * Constructs a new Team with the given name.
     * 
     * @param name The name of the team
     * @throws IllegalArgumentException if name is null or empty
     */
    public Team(@NotNull String name) {
        validateTeamName(name);
        this.name = name;
    }

    // Getters

    /**
     * Gets the name of the team.
     * 
     * @return The team name (never null or empty)
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Gets an unmodifiable view of the team members.
     * This operation is thread-safe.
     * 
     * @return An unmodifiable set of member UUIDs
     */
    @NotNull
    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(Set.copyOf(members));
    }

    /**
     * Gets the ID of the island assigned to this team.
     * 
     * @return The island ID, or null if no island is assigned
     */
    @Nullable
    public String getIslandId() {
        return islandId;
    }

    /**
     * Gets the points accumulated by this team.
     * 
     * @return The team's points (always non-negative)
     */
    public int getPoints() {
        return points;
    }

    /**
     * Gets the number of players in this team.
     * This operation is thread-safe.
     * 
     * @return The team size
     */
    public int getSize() {
        return members.size();
    }

    // Member management methods

    /**
     * Adds a player to the team.
     * This operation is thread-safe.
     * 
     * @param playerUuid The UUID of the player to add
     * @return true if the player was added, false if they were already a member
     * @throws IllegalArgumentException if playerUuid is null
     */
    public boolean addMember(@NotNull UUID playerUuid) {
        validatePlayerUuid(playerUuid);
        return members.add(playerUuid);
    }

    /**
     * Removes a player from the team.
     * This operation is thread-safe.
     * 
     * @param playerUuid The UUID of the player to remove
     * @return true if the player was removed, false if they were not a member
     * @throws IllegalArgumentException if playerUuid is null
     */
    public boolean removeMember(@NotNull UUID playerUuid) {
        validatePlayerUuid(playerUuid);
        return members.remove(playerUuid);
    }

    /**
     * Checks if a player is a member of this team.
     * This operation is thread-safe.
     * 
     * @param playerUuid The UUID of the player to check
     * @return true if the player is a member, false otherwise
     * @throws IllegalArgumentException if playerUuid is null
     */
    public boolean isMember(@NotNull UUID playerUuid) {
        validatePlayerUuid(playerUuid);
        return members.contains(playerUuid);
    }

    /**
     * Removes all members from the team.
     * This operation is thread-safe.
     */
    public void clearMembers() {
        members.clear();
    }

    // Island assignment methods

    /**
     * Sets the ID of the island assigned to this team.
     * 
     * @param islandId The island ID to assign, or null to clear assignment
     */
    public void setIslandId(@Nullable String islandId) {
        this.islandId = (islandId != null && islandId.trim().isEmpty()) ? null : islandId;
    }

    /**
     * Clears the island assignment for this team.
     */
    public void clearIslandAssignment() {
        this.islandId = null;
    }

    /**
     * Checks if this team has an island assigned to it.
     * 
     * @return true if an island is assigned, false otherwise
     */
    public boolean hasIsland() {
        return islandId != null && !islandId.trim().isEmpty();
    }

    // Scoring methods

    /**
     * Sets the points for this team.
     * 
     * @param points The new point value (must be non-negative)
     * @throws IllegalArgumentException if points is negative
     */
    public void setPoints(int points) {
        if (points < 0) {
            throw new IllegalArgumentException("Points cannot be negative");
        }
        this.points = points;
    }

    /**
     * Adds points to this team's score.
     * The resulting score will never go below zero.
     * 
     * @param pointsToAdd The number of points to add (can be negative to subtract)
     * @return The new points total
     */
    public int addPoints(int pointsToAdd) {
        this.points = Math.max(0, this.points + pointsToAdd);
        return this.points;
    }

    /**
     * Resets the team's points to zero.
     */
    public void resetPoints() {
        this.points = 0;
    }

    // State check methods

    /**
     * Checks if this team has any members.
     * 
     * @return true if the team has at least one member, false otherwise
     */
    public boolean hasMembers() {
        return !members.isEmpty();
    }

    /**
     * Checks if this team is empty (no members).
     * 
     * @return true if the team has no members, false otherwise
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    // Private validation methods

    private static void validateTeamName(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(TEAM_NAME_FIELD + " cannot be null or empty");
        }
    }

    private static void validatePlayerUuid(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            throw new IllegalArgumentException(PLAYER_UUID_FIELD + " cannot be null");
        }
    }

    // Object methods

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Team other && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Team{name='%s', members=%d, island='%s', points=%d}".formatted(
                name,
                members.size(),
                islandId != null ? islandId : "none",
                points);
    }
}