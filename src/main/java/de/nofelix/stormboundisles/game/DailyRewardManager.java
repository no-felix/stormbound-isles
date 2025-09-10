package de.nofelix.stormboundisles.game;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

    /**
     * Represents a player's daily activity data.
     * Thread-safe through atomic operations and synchronized methods.
     */
    private static class PlayerDailyData {
        private final AtomicLong totalOnlineTimeMs = new AtomicLong(0);
        private volatile long sessionStartTime = 0;
        private volatile boolean diedToday = false;
        private volatile boolean isOnline = false;
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

        currentDay = LocalDate.now();
        lastMidnightCheck.set(System.currentTimeMillis());

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
    }

    /**
     * Server tick handler - only checks for midnight once per minute.
     */
    private static void onServerTick(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();

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
    }

    /**
     * Processes daily rewards when midnight occurs.
     */
    private static void processDailyRewards(MinecraftServer server) {
        try {
            LOGGER.info("Processing daily survival/no-death rewards");

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

                // Check if player survived (active, didn't die, currently alive)
                boolean died = data.getDiedToday();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(memberId);
                boolean currentlyAlive = player != null && player.isAlive();

                if (!died && currentlyAlive) {
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
        info.put("currentDay", currentDay.toString());
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
