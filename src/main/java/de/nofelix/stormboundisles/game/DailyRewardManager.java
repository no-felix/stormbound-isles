package de.nofelix.stormboundisles.game;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages daily reward tracking and distribution for players and teams.
 * 
 * This system tracks player activity and survival from midnight to midnight
 * using thread-safe operations and efficient data structures. It handles:
 * - Player session tracking (join/leave times)
 * - Daily online time accumulation
 * - Death tracking per day
 * - Midnight reward calculation and distribution
 * - Automatic cleanup of stale data
 * - Persistent storage to survive server restarts
 */
public final class DailyRewardManager {

    private static final Logger LOGGER = StormboundIslesMod.LOGGER;

    // Thread-safe data structures
    private static final Map<UUID, PlayerDailyData> playerData = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> teamDeathCount = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> appliedPointsToday = new ConcurrentHashMap<>();

    // Cache the current day to avoid frequent calculations
    private static volatile LocalDate currentDay;
    private static final AtomicLong lastMidnightCheck = new AtomicLong(0);

    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour
    private static volatile long lastCleanup = 0;

    // Persistence
    private static DailyRewardState persistentState;

    // NBT Keys
    private static final String NBT_CURRENT_DAY = "currentDay";
    private static final String NBT_LAST_MIDNIGHT_CHECK = "lastMidnightCheck";
    private static final String NBT_PLAYER_DATA = "playerData";
    private static final String NBT_TEAM_DEATH_COUNT = "teamDeathCount";
    private static final String NBT_APPLIED_POINTS_TODAY = "appliedPointsToday";
    private static final String NBT_PLAYER_ID = "playerId";
    private static final String NBT_TOTAL_ONLINE_TIME_MS = "totalOnlineTimeMs";
    private static final String NBT_SESSION_START_TIME = "sessionStartTime";
    private static final String NBT_DIED_TODAY = "diedToday";
    private static final String NBT_IS_ONLINE = "isOnline";
    private static final String NBT_ONE_HOUR_MESSAGE_SENT = "oneHourMessageSent";

    /**
     * Persistent state for daily reward data
     */
    private static class DailyRewardState extends PersistentState {
        private final Map<UUID, PlayerDailyData> savedPlayerData = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> savedTeamDeathCount = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> savedAppliedPointsToday = new ConcurrentHashMap<>();
        private LocalDate savedCurrentDay;
        private long savedLastMidnightCheck;

        public static DailyRewardState create() {
            return new DailyRewardState();
        }

        public static DailyRewardState fromNbt(NbtCompound nbt) {
            DailyRewardState state = new DailyRewardState();

            // Load current day
            if (nbt.contains(NBT_CURRENT_DAY)) {
                state.savedCurrentDay = LocalDate.parse(nbt.getString(NBT_CURRENT_DAY));
            }

            // Load last midnight check
            state.savedLastMidnightCheck = nbt.getLong(NBT_LAST_MIDNIGHT_CHECK);

            // Load player data
            if (nbt.contains(NBT_PLAYER_DATA)) {
                NbtList playerList = nbt.getList(NBT_PLAYER_DATA, NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < playerList.size(); i++) {
                    NbtCompound playerNbt = playerList.getCompound(i);
                    UUID playerId = playerNbt.getUuid(NBT_PLAYER_ID);

                    PlayerDailyData data = new PlayerDailyData();
                    data.totalOnlineTimeMs.set(playerNbt.getLong(NBT_TOTAL_ONLINE_TIME_MS));
                    data.sessionStartTime = playerNbt.getLong(NBT_SESSION_START_TIME);
                    data.diedToday = playerNbt.getBoolean(NBT_DIED_TODAY);
                    data.isOnline = playerNbt.getBoolean(NBT_IS_ONLINE);
                    data.oneHourMessageSent = playerNbt.getBoolean(NBT_ONE_HOUR_MESSAGE_SENT);

                    state.savedPlayerData.put(playerId, data);
                }
            }

            // Load team death count
            if (nbt.contains(NBT_TEAM_DEATH_COUNT)) {
                NbtCompound teamDeathsNbt = nbt.getCompound(NBT_TEAM_DEATH_COUNT);
                for (String teamName : teamDeathsNbt.getKeys()) {
                    state.savedTeamDeathCount.put(teamName, new AtomicInteger(teamDeathsNbt.getInt(teamName)));
                }
            }

            // Load applied points today
            if (nbt.contains(NBT_APPLIED_POINTS_TODAY)) {
                NbtCompound appliedPointsNbt = nbt.getCompound(NBT_APPLIED_POINTS_TODAY);
                for (String teamName : appliedPointsNbt.getKeys()) {
                    state.savedAppliedPointsToday.put(teamName, new AtomicInteger(appliedPointsNbt.getInt(teamName)));
                }
            }

            return state;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            // Save current day
            if (savedCurrentDay != null) {
                nbt.putString(NBT_CURRENT_DAY, savedCurrentDay.toString());
            }

            // Save last midnight check
            nbt.putLong(NBT_LAST_MIDNIGHT_CHECK, savedLastMidnightCheck);

            // Save player data
            NbtList playerList = new NbtList();
            for (Map.Entry<UUID, PlayerDailyData> entry : savedPlayerData.entrySet()) {
                NbtCompound playerNbt = new NbtCompound();
                playerNbt.putUuid(NBT_PLAYER_ID, entry.getKey());

                PlayerDailyData data = entry.getValue();
                playerNbt.putLong(NBT_TOTAL_ONLINE_TIME_MS, data.totalOnlineTimeMs.get());
                playerNbt.putLong(NBT_SESSION_START_TIME, data.sessionStartTime);
                playerNbt.putBoolean(NBT_DIED_TODAY, data.diedToday);
                playerNbt.putBoolean(NBT_IS_ONLINE, data.isOnline);
                playerNbt.putBoolean(NBT_ONE_HOUR_MESSAGE_SENT, data.oneHourMessageSent);

                playerList.add(playerNbt);
            }
            nbt.put(NBT_PLAYER_DATA, playerList);

            // Save team death count
            NbtCompound teamDeathsNbt = new NbtCompound();
            for (Map.Entry<String, AtomicInteger> entry : savedTeamDeathCount.entrySet()) {
                teamDeathsNbt.putInt(entry.getKey(), entry.getValue().get());
            }
            nbt.put(NBT_TEAM_DEATH_COUNT, teamDeathsNbt);

            // Save applied points today
            NbtCompound appliedPointsNbt = new NbtCompound();
            for (Map.Entry<String, AtomicInteger> entry : savedAppliedPointsToday.entrySet()) {
                appliedPointsNbt.putInt(entry.getKey(), entry.getValue().get());
            }
            nbt.put(NBT_APPLIED_POINTS_TODAY, appliedPointsNbt);

            return nbt;
        }

        public void updateFromCurrentState() {
            this.savedPlayerData.clear();
            this.savedPlayerData.putAll(playerData);
            this.savedTeamDeathCount.clear();
            this.savedTeamDeathCount.putAll(teamDeathCount);
            this.savedAppliedPointsToday.clear();
            this.savedAppliedPointsToday.putAll(appliedPointsToday);
            this.savedCurrentDay = currentDay;
            this.savedLastMidnightCheck = lastMidnightCheck.get();
        }

        public static void loadToCurrentState(DailyRewardState state) {
            if (state != null) {
                playerData.clear();
                playerData.putAll(state.savedPlayerData);
                teamDeathCount.clear();
                teamDeathCount.putAll(state.savedTeamDeathCount);
                appliedPointsToday.clear();
                appliedPointsToday.putAll(state.savedAppliedPointsToday);
                currentDay = state.savedCurrentDay;
                lastMidnightCheck.set(state.savedLastMidnightCheck);
            }
        }
    }

    /**
     * Represents a player's daily activity data.
     * Thread-safe through atomic operations and synchronized methods.
     */
    private static class PlayerDailyData {
        private final AtomicLong totalOnlineTimeMs = new AtomicLong(0);
        private volatile long sessionStartTime = 0;
        private volatile boolean diedToday = false;
        private volatile boolean isOnline = false;
        private volatile boolean oneHourMessageSent = false; // Track if 1-hour message was sent
        private final Object sessionLock = new Object();

        /**
         * Starts a new session for this player.
         */
        void startSession() {
            synchronized (sessionLock) {
                if (!isOnline) {
                    sessionStartTime = System.currentTimeMillis();
                    isOnline = true;
                }
            }
        }

        /**
         * Ends the current session and accumulates the online time.
         */
        void endSession() {
            synchronized (sessionLock) {
                if (isOnline) {
                    long sessionDuration = System.currentTimeMillis() - sessionStartTime;
                    totalOnlineTimeMs.addAndGet(Math.max(0, sessionDuration));
                    isOnline = false;
                    sessionStartTime = 0;
                }
            }
        }

        /**
         * Gets the total online time including the current session if active.
         */
        long getTotalOnlineTimeMs() {
            long base = totalOnlineTimeMs.get();
            synchronized (sessionLock) {
                if (isOnline) {
                    long currentSession = System.currentTimeMillis() - sessionStartTime;
                    return base + Math.max(0, currentSession);
                }
            }
            return base;
        }

        /**
         * Resets all data for a new day, preserving current session.
         */
        void resetForNewDay() {
            synchronized (sessionLock) {
                if (isOnline) {
                    // If player is currently online, start fresh session from now
                    sessionStartTime = System.currentTimeMillis();
                }
                totalOnlineTimeMs.set(0);
                diedToday = false;
                oneHourMessageSent = false; // Reset 1-hour message flag for new day
            }
        }

        void markDeath() {
            diedToday = true;
        }

        boolean getDiedToday() {
            return diedToday;
        }

        boolean isActive() {
            return getTotalOnlineTimeMs() >= (ConfigManager.getActivityThresholdSeconds() * 1000L);
        }
    }

    private DailyRewardManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Initializes the daily reward system and registers event listeners.
     */
    @Initialize(priority = 1450, description = "Initialize daily reward manager")
    public static void initialize() {
        LOGGER.info("Initializing DailyRewardManager");

        // Load persistent state
        loadPersistentState();

        // Initialize current day if not loaded
        if (currentDay == null) {
            currentDay = LocalDate.now();
        }

        // Initialize last midnight check if not loaded
        if (lastMidnightCheck.get() == 0) {
            lastMidnightCheck.set(System.currentTimeMillis());
        }

        // Register player join/leave events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID playerId = handler.getPlayer().getUuid();
            playerData.computeIfAbsent(playerId, k -> new PlayerDailyData()).startSession();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler != null && handler.getPlayer() != null) {
                UUID playerId = handler.getPlayer().getUuid();
                PlayerDailyData data = playerData.get(playerId);
                if (data != null) {
                    data.endSession();
                }
            }
        });

        // Register server tick for midnight checks
        ServerTickEvents.END_SERVER_TICK.register(DailyRewardManager::onServerTick);

        // Register server shutdown to save data
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, saving daily reward data...");
            savePersistentState();
        });

        LOGGER.info("DailyRewardManager initialized successfully");
    }

    /**
     * Loads the persistent state from disk.
     */
    private static void loadPersistentState() {
        try {
            // This will be called during server initialization
            // We'll load the state when the server is available
            LOGGER.debug("Persistent state will be loaded when server is available");
        } catch (Exception e) {
            LOGGER.error("Failed to load persistent state", e);
        }
    }

    /**
     * Loads persistent state using the server's persistent state manager.
     */
    public static void loadPersistentState(MinecraftServer server) {
        try {
            // Get the overworld's persistent state manager
            PersistentStateManager stateManager = server.getOverworld().getPersistentStateManager();
            persistentState = stateManager.getOrCreate(DailyRewardState::fromNbt, DailyRewardState::create,
                    "daily_rewards");

            // Load the saved data into current state
            DailyRewardState.loadToCurrentState(persistentState);

            LOGGER.info("Daily reward persistent state loaded successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to load persistent state from server", e);
        }
    }

    /**
     * Saves the current state to persistent storage.
     */
    private static void savePersistentState() {
        try {
            if (persistentState != null) {
                persistentState.updateFromCurrentState();
                persistentState.markDirty();
                LOGGER.debug("Daily reward state saved to disk");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save persistent state", e);
        }
    }

    /**
     * Records a player death for daily tracking.
     */
    public static void recordPlayerDeath(UUID playerId, String teamName) {
        if (playerId == null || teamName == null)
            return;

        PlayerDailyData data = playerData.computeIfAbsent(playerId, k -> new PlayerDailyData());
        data.markDeath();

        teamDeathCount.computeIfAbsent(teamName, k -> new AtomicInteger(0)).incrementAndGet();

        // Save state after recording death
        savePersistentState();
    }

    /**
     * Server tick handler - only checks for midnight once per minute.
     */
    private static void onServerTick(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();

        // Load persistent state if not loaded yet
        if (persistentState == null) {
            loadPersistentState(server);
        }

        // Check for 1-hour milestone messages
        checkOneHourMilestones(server);

        // Only check for day change once per minute to reduce overhead
        if (currentTime - lastMidnightCheck.get() > 60000L) { // 60 seconds
            lastMidnightCheck.set(currentTime);

            LocalDate today = LocalDate.now();
            if (!today.equals(currentDay)) {
                LOGGER.info("Day changed from {} to {}, processing daily rewards", currentDay, today);
                processDailyRewards(server);
                currentDay = today;
            }
        }

        // Periodic cleanup of stale data
        if (currentTime - lastCleanup > CLEANUP_INTERVAL_MS) {
            cleanupStaleData();
            lastCleanup = currentTime;
        }

        // Periodic save of data (every 5 minutes)
        if (currentTime - lastCleanup > 300000L) { // 5 minutes
            savePersistentState();
        }
    }

    /**
     * Checks if any online players have reached the 1-hour milestone and sends them
     * a message.
     */
    private static void checkOneHourMilestones(MinecraftServer server) {
        final long ONE_HOUR_MS = 3_600_000L; // 1 hour in milliseconds

        for (Map.Entry<UUID, PlayerDailyData> entry : playerData.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerDailyData data = entry.getValue();

            // Only check online players who haven't received the message yet
            if (data.isOnline && !data.oneHourMessageSent) {
                long totalTime = data.getTotalOnlineTimeMs();

                if (totalTime >= ONE_HOUR_MS) {
                    // Mark message as sent
                    data.oneHourMessageSent = true;

                    // Send message to player
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                    if (player != null) {
                        String message = "§6🎉 Congratulations! §fYou've reached §e1 hour §f of playtime today!";
                        player.sendMessage(Text.literal(message), false);
                        LOGGER.debug("Sent 1-hour milestone message to player {}", player.getName().getString());
                    }
                }
            }
        }
    }

    /**
     * Broadcasts player session times to all players in chat.
     */
    private static void broadcastPlayerSessionTimes(MinecraftServer server) {
        try {
            // Send header message
            server.getPlayerManager().broadcast(Text.literal("§6§l=== DAILY SESSION SUMMARY ==="), false);

            // Group players by team
            Map<String, List<Map.Entry<UUID, PlayerDailyData>>> playersByTeam = new HashMap<>();

            for (Map.Entry<UUID, PlayerDailyData> entry : playerData.entrySet()) {
                UUID playerId = entry.getKey();
                String teamName = findPlayerTeam(playerId);
                playersByTeam.computeIfAbsent(teamName, k -> new ArrayList<>()).add(entry);
            }

            // Display each team's players
            playersByTeam.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> displayTeamPlayers(server, entry.getKey(), entry.getValue()));

            // Send footer
            server.getPlayerManager().broadcast(Text.literal("§6§l============================"), false);

        } catch (Exception e) {
            LOGGER.error("Error broadcasting player session times", e);
        }
    }

    /**
     * Finds which team a player belongs to.
     */
    private static String findPlayerTeam(UUID playerId) {
        for (Team team : DataManager.getTeams().values()) {
            if (team.getMembers().contains(playerId)) {
                return team.getName();
            }
        }
        return "No Team";
    }

    /**
     * Displays all players for a specific team.
     */
    private static void displayTeamPlayers(MinecraftServer server, String teamName,
            List<Map.Entry<UUID, PlayerDailyData>> teamPlayers) {
        // Send team header
        server.getPlayerManager().broadcast(Text.literal(String.format("§e§lTeam %s:", teamName)), false);

        // Sort and display players
        teamPlayers.stream()
                .sorted((a, b) -> Long.compare(b.getValue().getTotalOnlineTimeMs(),
                        a.getValue().getTotalOnlineTimeMs()))
                .forEach(entry -> displayPlayerTime(server, entry.getKey(), entry.getValue()));
    }

    /**
     * Displays a single player's session time.
     */
    private static void displayPlayerTime(MinecraftServer server, UUID playerId, PlayerDailyData data) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        String playerName = player != null ? player.getName().getString() : "Unknown";

        long totalMs = data.getTotalOnlineTimeMs();
        long hours = totalMs / (1000 * 60 * 60);
        long minutes = (totalMs / (1000 * 60)) % 60;
        String timeString = hours > 0 ? String.format("%dh %dm", hours, minutes) : String.format("%dm", minutes);

        String status = data.isOnline ? "§a●" : "§7●";
        String playerInfo = String.format("  %s §f%s §7- §e%s", status, playerName, timeString);

        server.getPlayerManager().broadcast(Text.literal(playerInfo), false);
    }

    /**
     * Processes daily rewards when midnight occurs.
     */
    private static void processDailyRewards(MinecraftServer server) {
        try {
            LOGGER.info("Processing daily survival/no-death rewards");

            // Broadcast player session times before processing rewards
            broadcastPlayerSessionTimes(server);

            // Calculate rewards for each team
            Map<String, Team> teams = DataManager.getTeams();
            if (teams.isEmpty()) {
                LOGGER.debug("No teams found, skipping daily rewards");
                return;
            }

            // Find minimum team size for fair comparison
            int minTeamSize = teams.values().stream()
                    .mapToInt(team -> team.getMembers().size())
                    .min()
                    .orElse(0);

            for (Team team : teams.values()) {
                calculateAndAwardTeamRewards(server, team, minTeamSize);
            }

            // Reset all daily data for new day
            resetDailyData();

            // Save state after processing rewards
            savePersistentState();

            LOGGER.info("Daily rewards processing completed");
        } catch (Exception e) {
            LOGGER.error("Error processing daily rewards", e);
        }
    }

    /**
     * Calculates and awards daily rewards for a specific team.
     */
    private static void calculateAndAwardTeamRewards(MinecraftServer server, Team team, int minTeamSize) {
        String teamName = team.getName();
        Set<UUID> members = team.getMembers();

        if (members.isEmpty()) {
            return;
        }

        int activeMembers = 0;
        int survivors = 0;

        // Count active players and survivors
        for (UUID memberId : members) {
            PlayerDailyData data = playerData.get(memberId);
            if (data == null)
                continue;

            boolean isActive = data.isActive();
            if (isActive) {
                activeMembers++;

                // Check if player survived (active and didn't die during the day)
                boolean died = data.getDiedToday();

                if (!died) {
                    survivors++;
                }
            }
        }

        // Calculate rewards
        int survivalPoints = survivors * ConfigManager.getSurvivePerPlayerPvP();
        int noDeathBonus = 0;

        // No-death bonus requires minimum active players and zero team deaths
        int teamDeaths = teamDeathCount.getOrDefault(teamName, new AtomicInteger(0)).get();
        int requiredActiveMembers = Math.min(members.size(), minTeamSize);

        if (teamDeaths == 0 && activeMembers >= requiredActiveMembers && requiredActiveMembers > 0) {
            noDeathBonus = members.size() * ConfigManager.getDailyNoDeathBonusPerPlayer();
        }

        int totalReward = survivalPoints + noDeathBonus;

        // Apply daily point cap
        int dailyCap = ConfigManager.getMaxPointsPerDay();
        int alreadyAwarded = appliedPointsToday.getOrDefault(teamName, new AtomicInteger(0)).get();
        int remainingCap = Math.max(0, dailyCap - alreadyAwarded);
        int actualReward = Math.min(totalReward, remainingCap);

        // Award points and notify
        if (actualReward > 0) {
            team.addPoints(actualReward);
            ScoreboardManager.updateTeamScore(teamName);

            // Format: Team <name> gained <points> points (Daily: <survival> survival,
            // <bonus> no-death bonus)
            String formattedMessage = String.format(
                    "Team %s gained §e%d §apoints §7(§eDaily: §f%d survival§7, §f%d no-death bonus§7)",
                    teamName,
                    actualReward,
                    Math.min(survivalPoints, actualReward),
                    Math.min(noDeathBonus, actualReward - Math.min(survivalPoints, actualReward)));

            server.getPlayerManager().broadcast(Text.literal(formattedMessage), false);

            appliedPointsToday.computeIfAbsent(teamName, k -> new AtomicInteger(0))
                    .addAndGet(actualReward);

            DataManager.saveAll();

            LOGGER.info("Team {} awarded {} daily points ({} survivors, {} active, {} deaths)",
                    teamName, actualReward, survivors, activeMembers, teamDeaths);
        }
    }

    /**
     * Resets all daily tracking data for a new day.
     */
    private static void resetDailyData() {
        // Reset player data but preserve active sessions
        playerData.values().forEach(PlayerDailyData::resetForNewDay);

        // Reset team counters
        teamDeathCount.values().forEach(counter -> counter.set(0));
        appliedPointsToday.values().forEach(counter -> counter.set(0));

        LOGGER.debug("Reset daily data for {} players and {} teams",
                playerData.size(), teamDeathCount.size());
    }

    /**
     * Removes data for players who haven't been online for over 7 days.
     */
    private static void cleanupStaleData() {
        Set<UUID> toRemove = new HashSet<>();
        for (Map.Entry<UUID, PlayerDailyData> entry : playerData.entrySet()) {
            PlayerDailyData data = entry.getValue();
            if (!data.isOnline && data.getTotalOnlineTimeMs() == 0) {
                toRemove.add(entry.getKey());
            }
        }

        toRemove.forEach(playerData::remove);

        if (!toRemove.isEmpty()) {
            LOGGER.debug("Cleaned up {} stale player records", toRemove.size());
        }
    }

    /**
     * Gets debug information about current daily tracking state.
     */
    public static Map<String, Object> getDebugInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("current_day", currentDay.toString());
        info.put("trackedPlayers", playerData.size());

        // Count teams that have active players (not just teams with deaths)
        Set<String> teamsWithActivePlayers = new HashSet<>();
        for (Map.Entry<UUID, PlayerDailyData> entry : playerData.entrySet()) {
            UUID playerId = entry.getKey();

            // Find which team this player belongs to
            for (Team team : DataManager.getTeams().values()) {
                if (team.getMembers().contains(playerId)) {
                    teamsWithActivePlayers.add(team.getName());
                    break;
                }
            }
        }
        info.put("trackedTeams", teamsWithActivePlayers.size());
        info.put("teamsWithDeaths", teamDeathCount.size());

        // Active players count and detailed info
        long activeCount = 0;
        long onlineCount = 0;
        long totalOnlineTimeHours = 0;
        int activityThresholdSeconds = ConfigManager.getActivityThresholdSeconds();

        for (PlayerDailyData data : playerData.values()) {
            if (data.isOnline)
                onlineCount++;
            if (data.isActive())
                activeCount++;
            totalOnlineTimeHours += data.getTotalOnlineTimeMs() / (1000 * 60 * 60); // Convert to hours
        }

        info.put("activePlayers", activeCount);
        info.put("onlinePlayers", onlineCount);
        info.put("totalOnlineTimeHours", totalOnlineTimeHours);
        info.put("activityThresholdSeconds", activityThresholdSeconds);

        return info;
    }
}
