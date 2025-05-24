package de.nofelix.stormboundisles.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages loading and saving persistent game data, including team information,
 * island definitions, and the current game state (phase and progress).
 * 
 * Data is stored in JSON files within the world save directory
 * (`world/stormboundisles`). This class provides thread-safe access to
 * game data and handles automatic persistence operations.
 * 
 * Example usage:
 * ```java
 * Team team = DataManager.getTeam("red");
 * DataManager.putTeam(new Team("blue", players));
 * DataManager.saveAll();
 * ```
 */
public final class DataManager {

    // Constants
    private static final String DATA_DIR_NAME = "stormboundisles";
    private static final String ISLANDS_FILENAME = "islands.json";
    private static final String TEAMS_FILENAME = "teams.json";
    private static final String GAME_STATE_FILENAME = "game_state.json";

    // Validation field names
    private static final String TEAM_NAME_FIELD = "Team name";
    private static final String ISLAND_ID_FIELD = "Island ID";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Logger LOGGER = StormboundIslesMod.LOGGER;

    // Type tokens for JSON deserialization
    private static final Type TEAM_MAP_TYPE = new TypeToken<Map<String, Team>>() {
    }.getType();
    private static final Type ISLAND_MAP_TYPE = new TypeToken<Map<String, Island>>() {
    }.getType();

    // Data storage - Using ConcurrentHashMap for thread safety
    private static final Map<String, Team> teams = new ConcurrentHashMap<>();
    private static final Map<String, Island> islands = new ConcurrentHashMap<>();

    private DataManager() {
    }

    // Initialization methods

    /**
     * Initializes the DataManager and loads all persistent data.
     * This method is automatically called during mod initialization.
     */
    @Initialize(priority = 2000)
    public static void initialize() {
        LOGGER.info("Initializing DataManager...");
        loadAll();
        LOGGER.info("DataManager initialized successfully");
    }

    // Public API methods

    /**
     * Returns an unmodifiable view of the teams map.
     * Modifications should be done via {@link #putTeam(Team)}.
     *
     * @return An unmodifiable map of team names to Team objects
     */
    @NotNull
    public static Map<String, Team> getTeams() {
        return Collections.unmodifiableMap(teams);
    }

    /**
     * Returns an unmodifiable view of the islands map.
     * Modifications should be done via {@link #putIsland(Island)}.
     *
     * @return An unmodifiable map of island IDs to Island objects
     */
    @NotNull
    public static Map<String, Island> getIslands() {
        return Collections.unmodifiableMap(islands);
    }

    /**
     * Gets a team by its name.
     *
     * @param teamName The name of the team to retrieve
     * @return The Team object, or null if no team exists with the given name
     * @throws IllegalArgumentException if teamName is null or empty
     */
    @Nullable
    public static Team getTeam(@NotNull String teamName) {
        validateNotNullOrEmpty(teamName, TEAM_NAME_FIELD);
        return teams.get(teamName);
    }

    /**
     * Gets an island by its ID.
     *
     * @param islandId The ID of the island to retrieve
     * @return The Island object, or null if no island exists with the given ID
     * @throws IllegalArgumentException if islandId is null or empty
     */
    @Nullable
    public static Island getIsland(@NotNull String islandId) {
        validateNotNullOrEmpty(islandId, ISLAND_ID_FIELD);
        return islands.get(islandId);
    }

    /**
     * Adds or updates a team in the teams collection.
     * This operation is thread-safe.
     *
     * @param team The team to add or update
     * @throws IllegalArgumentException if the team has a null/empty name
     */
    public static void putTeam(@NotNull Team team) {
        validateNotNullOrEmpty(team.getName(), TEAM_NAME_FIELD);

        teams.put(team.getName(), team);
        LOGGER.debug("Added/updated team: {}", team.getName());
    }

    /**
     * Adds or updates an island in the islands collection.
     * This operation is thread-safe.
     *
     * @param island The island to add or update
     * @throws IllegalArgumentException if the island has a null/empty ID
     */
    public static void putIsland(@NotNull Island island) {
        validateNotNullOrEmpty(island.getId(), ISLAND_ID_FIELD);

        islands.put(island.getId(), island);
        LOGGER.debug("Added/updated island: {}", island.getId());
    }

    /**
     * Removes a team from the teams collection.
     *
     * @param teamName The name of the team to remove
     * @return The removed team, or null if no team existed with that name
     * @throws IllegalArgumentException if teamName is null or empty
     */
    @Nullable
    public static Team removeTeam(@NotNull String teamName) {
        validateNotNullOrEmpty(teamName, TEAM_NAME_FIELD);
        Team removed = teams.remove(teamName);
        if (removed != null) {
            LOGGER.debug("Removed team: {}", teamName);
        }
        return removed;
    }

    /**
     * Removes an island from the islands collection.
     *
     * @param islandId The ID of the island to remove
     * @return The removed island, or null if no island existed with that ID
     * @throws IllegalArgumentException if islandId is null or empty
     */
    @Nullable
    public static Island removeIsland(@NotNull String islandId) {
        validateNotNullOrEmpty(islandId, ISLAND_ID_FIELD);
        Island removed = islands.remove(islandId);
        if (removed != null) {
            LOGGER.debug("Removed island: {}", islandId);
        }
        return removed;
    }

    /**
     * Clears all teams from the in-memory collection.
     * Does not automatically persist this change; call {@link #saveTeams()} if
     * needed.
     */
    public static void clearTeams() {
        int count = teams.size();
        teams.clear();
        LOGGER.info("Cleared {} teams from memory", count);
    }

    /**
     * Clears all islands from the in-memory collection.
     * Does not automatically persist this change; call {@link #saveIslands()} if
     * needed.
     */
    public static void clearIslands() {
        int count = islands.size();
        islands.clear();
        LOGGER.info("Cleared {} islands from memory", count);
    }

    // Data persistence methods

    /**
     * Loads all data (islands, teams, game state) from the default directory
     * structure.
     * Uses the game directory provided by FabricLoader.
     */
    public static void loadAll() {
        Path runDir = FabricLoader.getInstance().getGameDir();
        load(runDir);
    }

    /**
     * Loads all data from the specified directory structure.
     * If files don't exist or are invalid, proceeds with default/empty data.
     *
     * @param runDir The base directory where the game is running
     */
    public static void load(@NotNull Path runDir) {
        LOGGER.info("Loading Stormbound Isles data from: {}", runDir);

        try {
            Path dataDir = ensureDataDirectory(runDir);

            loadIslands(dataDir);
            loadTeams(dataDir);
            loadGameState(dataDir);

            LOGGER.info("Successfully loaded data - Teams: {}, Islands: {}",
                    teams.size(), islands.size());

        } catch (IOException e) {
            LOGGER.error("Fatal error accessing data directory '{}'. Data loading aborted.",
                    DATA_DIR_NAME, e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during data loading", e);
        }
    }

    /**
     * Saves all data to the default directory structure.
     * Uses the game directory provided by FabricLoader.
     */
    public static void saveAll() {
        Path runDir = FabricLoader.getInstance().getGameDir();
        saveAll(runDir);
    }

    /**
     * Saves all data to the specified directory structure.
     *
     * @param runDir The base directory where the game is running
     */
    public static void saveAll(@NotNull Path runDir) {
        LOGGER.info("Saving all Stormbound Isles data to: {}", runDir);

        try {
            Path dataDir = ensureDataDirectory(runDir);

            saveIslands(dataDir);
            saveTeams(dataDir);
            saveGameState(dataDir);

            LOGGER.info("Successfully saved all data");

        } catch (IOException e) {
            LOGGER.error("Fatal error accessing data directory '{}'. Data saving aborted.",
                    DATA_DIR_NAME, e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during data saving", e);
        }
    }

    /**
     * Saves only the teams data to the default directory structure.
     */
    public static void saveTeams() {
        Path runDir = FabricLoader.getInstance().getGameDir();
        try {
            Path dataDir = ensureDataDirectory(runDir);
            saveTeams(dataDir);
        } catch (IOException e) {
            LOGGER.error("Error saving teams data", e);
        }
    }

    /**
     * Saves only the islands data to the default directory structure.
     */
    public static void saveIslands() {
        Path runDir = FabricLoader.getInstance().getGameDir();
        try {
            Path dataDir = ensureDataDirectory(runDir);
            saveIslands(dataDir);
        } catch (IOException e) {
            LOGGER.error("Error saving islands data", e);
        }
    }

    /**
     * Saves only the current game state to the default directory structure.
     */
    public static void saveGameState() {
        Path runDir = FabricLoader.getInstance().getGameDir();
        try {
            Path dataDir = ensureDataDirectory(runDir);
            saveGameState(dataDir);
        } catch (IOException e) {
            LOGGER.error("Error saving game state", e);
        }
    }

    // Private helper methods

    /**
     * Validates that a string is not null or empty.
     *
     * @param value     The string to validate
     * @param fieldName The name of the field for error messages
     * @throws IllegalArgumentException if the value is null or empty
     */
    private static void validateNotNullOrEmpty(@Nullable String value, @NotNull String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
    }

    /**
     * Creates and ensures the existence of the data directory.
     *
     * @param runDir The base directory for the game
     * @return The Path to the data directory
     * @throws IOException if directory creation fails
     */
    @NotNull
    private static Path ensureDataDirectory(@NotNull Path runDir) throws IOException {
        Path worldDir = runDir.resolve("world");
        Path baseDir = Files.isDirectory(worldDir) ? worldDir : runDir;
        Path dataDir = baseDir.resolve(DATA_DIR_NAME);

        Files.createDirectories(dataDir);
        return dataDir;
    }

    /**
     * Loads islands data from the JSON file.
     * Clears current in-memory islands before loading.
     *
     * @param dataDir The data directory containing the islands file
     */
    private static void loadIslands(@NotNull Path dataDir) {
        Path islandsPath = dataDir.resolve(ISLANDS_FILENAME);
        islands.clear();

        if (!Files.exists(islandsPath)) {
            LOGGER.info("Islands file {} not found. Starting with empty island data.",
                    ISLANDS_FILENAME);
            return;
        }

        try (var reader = Files.newBufferedReader(islandsPath, StandardCharsets.UTF_8)) {
            Map<String, Island> loadedIslands = GSON.fromJson(reader, ISLAND_MAP_TYPE);

            if (loadedIslands != null) {
                islands.putAll(loadedIslands);
                LOGGER.info("Loaded {} islands from {}", islands.size(), ISLANDS_FILENAME);
            } else {
                LOGGER.warn("Islands file {} contained null data", ISLANDS_FILENAME);
            }

        } catch (IOException e) {
            LOGGER.error("Could not read islands file: {}", islandsPath, e);
        } catch (JsonParseException e) {
            LOGGER.error("Invalid JSON format in islands file: {}", islandsPath, e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error loading islands", e);
        }
    }

    /**
     * Loads teams data from the JSON file.
     * Clears current in-memory teams before loading.
     *
     * @param dataDir The data directory containing the teams file
     */
    private static void loadTeams(@NotNull Path dataDir) {
        Path teamsPath = dataDir.resolve(TEAMS_FILENAME);
        teams.clear();

        if (!Files.exists(teamsPath)) {
            LOGGER.info("Teams file {} not found. Starting with empty team data.",
                    TEAMS_FILENAME);
            return;
        }

        try (var reader = Files.newBufferedReader(teamsPath, StandardCharsets.UTF_8)) {
            Map<String, Team> loadedTeams = GSON.fromJson(reader, TEAM_MAP_TYPE);

            if (loadedTeams != null) {
                teams.putAll(loadedTeams);
                LOGGER.info("Loaded {} teams from {}", teams.size(), TEAMS_FILENAME);
            } else {
                LOGGER.warn("Teams file {} contained null data", TEAMS_FILENAME);
            }

        } catch (IOException e) {
            LOGGER.error("Could not read teams file: {}", teamsPath, e);
        } catch (JsonParseException e) {
            LOGGER.error("Invalid JSON format in teams file: {}", teamsPath, e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error loading teams", e);
        }
    }

    /**
     * Loads game state data from the JSON file and updates GameManager.
     *
     * @param dataDir The data directory containing the game state file
     */
    private static void loadGameState(@NotNull Path dataDir) {
        Path gameStatePath = dataDir.resolve(GAME_STATE_FILENAME);

        if (!Files.exists(gameStatePath)) {
            LOGGER.info("Game state file {} not found. Starting in LOBBY phase.",
                    GAME_STATE_FILENAME);
            GameManager.setPhaseWithoutReset(GamePhase.LOBBY, 0);
            return;
        }

        try (var reader = Files.newBufferedReader(gameStatePath, StandardCharsets.UTF_8)) {
            GameState gameState = GSON.fromJson(reader, GameState.class);

            if (gameState != null && gameState.phase != null) {
                GameManager.setPhaseWithoutReset(gameState.phase, gameState.phaseTicks);
                LOGGER.info("Loaded game state: Phase={}, Ticks={}",
                        gameState.phase, gameState.phaseTicks);
            } else {
                LOGGER.warn("Game state file contained invalid data. Using default state.");
                GameManager.setPhaseWithoutReset(GamePhase.LOBBY, 0);
            }

        } catch (IOException e) {
            LOGGER.error("Could not read game state file: {}", gameStatePath, e);
            GameManager.setPhaseWithoutReset(GamePhase.LOBBY, 0);
        } catch (JsonParseException e) {
            LOGGER.error("Invalid JSON format in game state file: {}", gameStatePath, e);
            GameManager.setPhaseWithoutReset(GamePhase.LOBBY, 0);
        } catch (Exception e) {
            LOGGER.error("Unexpected error loading game state", e);
            GameManager.setPhaseWithoutReset(GamePhase.LOBBY, 0);
        }
    }

    /**
     * Saves the current islands data to the JSON file.
     *
     * @param dataDir The data directory where the islands file should be saved
     */
    private static void saveIslands(@NotNull Path dataDir) {
        Path islandsPath = dataDir.resolve(ISLANDS_FILENAME);
        writeJsonToFile(islandsPath, islands, "islands");
        LOGGER.debug("Saved {} islands to {}", islands.size(), ISLANDS_FILENAME);
    }

    /**
     * Saves the current teams data to the JSON file.
     *
     * @param dataDir The data directory where the teams file should be saved
     */
    private static void saveTeams(@NotNull Path dataDir) {
        Path teamsPath = dataDir.resolve(TEAMS_FILENAME);
        writeJsonToFile(teamsPath, teams, "teams");
        LOGGER.debug("Saved {} teams to {}", teams.size(), TEAMS_FILENAME);
    }

    /**
     * Saves the current game state data to the JSON file.
     *
     * @param dataDir The data directory where the game state file should be saved
     */
    private static void saveGameState(@NotNull Path dataDir) {
        Path gameStatePath = dataDir.resolve(GAME_STATE_FILENAME);
        GameState gameState = new GameState(GameManager.phase, GameManager.getPhaseTicks());

        writeJsonToFile(gameStatePath, gameState, "game state");
        LOGGER.debug("Saved game state: Phase={}, Ticks={}",
                gameState.phase, gameState.phaseTicks);
    }

    /**
     * Utility method to write an object as JSON to a file.
     *
     * @param path     The Path where the file should be written
     * @param object   The object to serialize to JSON
     * @param dataType A descriptive name of the data type being saved
     */
    private static void writeJsonToFile(@NotNull Path path, @Nullable Object object,
            @NotNull String dataType) {
        try {
            String json = GSON.toJson(object);

            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(json);
            }

        } catch (IOException e) {
            LOGGER.error("Failed to write {} data to file: {}", dataType, path, e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error saving {} data to file: {}", dataType, path, e);
        }
    }

    // Inner classes

    /**
     * Record representing the game state for JSON serialization.
     * Contains the current game phase and phase progression in ticks.
     */
    private record GameState(GamePhase phase, int phaseTicks) {
    }
}