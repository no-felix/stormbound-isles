package de.nofelix.stormboundisles.game;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Team;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.Random;
import java.util.UUID;

/**
 * Manages the overall game flow, including phases, timers, player states, and
 * game events.
 */
public class GameManager {
    /** The current phase of the game. */
    public static GamePhase phase = GamePhase.LOBBY;
    /** The number of ticks elapsed in the current game phase. */
    private static int phaseTicks = 0;

    // BossBar related fields
    /** The server-side BossBar instance used to display phase information. */
    private static ServerBossBar phaseBar;

    // Countdown related fields
    /** Flag indicating if the pre-game countdown is active. */
    private static boolean isStarting = false;
    /** Remaining ticks in the pre-game countdown. */
    private static int countdownTicks = 0;

    /** Random number generator for various game mechanics. */
    private static final Random random = new Random();

    /**
     * Registers game manager event listeners.
     * Initializes BossBar restoration on server start and registers the server tick
     * listener.
     */
    public static void register() {
        StormboundIslesMod.LOGGER.info("Registering GameManager");
        // Restore bossbar on server start when loading from game_state
        ServerLifecycleEvents.SERVER_STARTED.register(GameManager::setupBossBar);
        ServerTickEvents.END_SERVER_TICK.register(GameManager::onServerTick);

        // Add player to bossbar when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (phaseBar != null && phase != GamePhase.ENDED) {
                phaseBar.addPlayer(handler.getPlayer());
            }
        });
    }

    /**
     * Returns the current phase tick count for persistence.
     * 
     * @return The number of ticks elapsed in the current phase.
     */
    public static int getPhaseTicks() {
        return phaseTicks;
    }

    /**
     * Restores the game phase and tick count without triggering phase transition
     * logic.
     * Used primarily for loading saved game state.
     * 
     * @param newPhase The phase to set.
     * @param ticks    The number of ticks elapsed in that phase.
     */
    public static void setPhaseWithoutReset(GamePhase newPhase, int ticks) {
        phase = newPhase;
        phaseTicks = ticks;
    }

    /**
     * Initiates a countdown before starting the build phase.
     * Displays a message and updates the BossBar.
     * 
     * @param server The Minecraft server instance.
     */
    public static void startCountdown(MinecraftServer server) {
        if (isStarting)
            return; // Prevent multiple countdowns

        isStarting = true;
        countdownTicks = ConfigManager.getGameCountdownDurationTicks();
        setupBossBar(server);
        server.getPlayerManager()
                .broadcast(Text.literal(
                        "Game starting in " + (ConfigManager.getGameCountdownDurationTicks() / 20) + " seconds..."),
                        false);
    }

    /**
     * Starts the game, transitioning to the BUILD phase.
     * Teleports players, sets game mode, broadcasts messages, and initializes the
     * scoreboard.
     * 
     * @param server The Minecraft server instance.
     */
    public static void startGame(MinecraftServer server) {
        StormboundIslesMod.LOGGER.info("Starting game");
        setPhase(GamePhase.BUILD, server);
        phaseTicks = 0;
        teleportPlayersToIslands(server);
        setAllPlayersGameMode(server, GameMode.SURVIVAL);
        server.getPlayerManager().broadcast(Text.literal("Game started! Build phase begins."), false);

        // Force update teams and scores to reflect current game state
        ScoreboardManager.updateAllTeams(server);
    }

    /**
     * Stops the game, transitioning to the ENDED phase.
     * Sets game mode, broadcasts messages, and removes the BossBar.
     * 
     * @param server The Minecraft server instance.
     */
    public static void stopGame(MinecraftServer server) {
        StormboundIslesMod.LOGGER.info("Stopping game");
        setPhase(GamePhase.ENDED, server);
        setAllPlayersGameMode(server, GameMode.ADVENTURE);
        server.getPlayerManager().broadcast(Text.literal("Game stopped."), false);

        // Remove bossbar completely - not just hide it
        removeBossBar();
    }

    /**
     * Completely removes the current BossBar if it exists.
     * This prevents ghost/duplicate BossBars from appearing.
     */
    private static void removeBossBar() {
        if (phaseBar != null) {
            phaseBar.clearPlayers();
            phaseBar.setVisible(false);
            phaseBar = null;
        }
    }

    /**
     * Transitions the game to a new phase, applying relevant game rules and player
     * states.
     * Updates the BossBar and saves the game state.
     * 
     * @param newPhase The target game phase.
     * @param server   The Minecraft server instance.
     */
    public static void setPhase(GamePhase newPhase, MinecraftServer server) {
        StormboundIslesMod.LOGGER.info("Changing phase from {} to {}", phase, newPhase);
        phase = newPhase;
        phaseTicks = 0;

        // Update bossbar and persist
        setupBossBar(server);
        DataManager.saveGameState();

        switch (phase) {
            case LOBBY, ENDED:
                setPvp(server, false);
                setAllPlayersGameMode(server, GameMode.ADVENTURE);
                break;
            case BUILD:
                setPvp(server, false);
                setAllPlayersGameMode(server, GameMode.SURVIVAL);
                break;
            case PVP:
                setPvp(server, true);
                setAllPlayersGameMode(server, GameMode.SURVIVAL);
                server.getPlayerManager().broadcast(Text.literal("PvP phase started!"), false);
                break;
        }
    }

    /**
     * Sets up or updates the phase BossBar.
     * Creates the BossBar if it doesn't exist and adds all online players.
     * Configures the BossBar's appearance based on the current game state.
     * 
     * @param server The Minecraft server instance.
     */
    public static void setupBossBar(MinecraftServer server) {
        // Remove existing BossBar to prevent duplicates
        removeBossBar();

        // Don't create a BossBar in ENDED phase unless we're starting a countdown
        if (phase == GamePhase.ENDED && !isStarting) {
            return;
        }

        // Create new BossBar with current phase settings
        phaseBar = new ServerBossBar(
                getBossBarTitle(), // Title based on current game phase
                getBossBarColor(), // Color based on current game phase
                BossBar.Style.PROGRESS // Style
        );

        // Set initial progress based on current phase
        if (isStarting) {
            phaseBar.setPercent((float) countdownTicks / ConfigManager.getGameCountdownDurationTicks());
        } else if (phase == GamePhase.BUILD && phaseTicks > 0) {
            phaseBar.setPercent(1.0f - ((float) phaseTicks / ConfigManager.getGameBuildPhaseTicks()));
        } else if (phase == GamePhase.PVP && phaseTicks > 0) {
            phaseBar.setPercent(1.0f - ((float) phaseTicks / ConfigManager.getGamePvpPhaseTicks()));
        } else {
            phaseBar.setPercent(1.0f);
        }

        phaseBar.setVisible(true); // Activate visibility
        phaseBar.setDarkenSky(false); // Don't darken the sky
        phaseBar.setThickenFog(false); // Don't thicken the fog
        phaseBar.setDragonMusic(false); // Disable dragon music

        // Add all players to the BossBar
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            phaseBar.addPlayer(player);
        }
    }

    /**
     * Gets the appropriate title text for the BossBar based on the current game
     * phase.
     * 
     * @return The Text object representing the BossBar title.
     */
    private static Text getBossBarTitle() {
        if (isStarting) {
            int seconds = countdownTicks / 20;
            return Text.literal("Starting in " + seconds + "s");
        }

        if (phase == GamePhase.BUILD && phaseTicks > 0) {
            int remainingMinutes = (ConfigManager.getGameBuildPhaseTicks() - phaseTicks) / (20 * 60);
            return Text.literal("Build Phase - " + formatTime(remainingMinutes));
        }

        if (phase == GamePhase.PVP && phaseTicks > 0) {
            int remainingMinutes = (ConfigManager.getGamePvpPhaseTicks() - phaseTicks) / (20 * 60);
            return Text.literal("PvP Phase - " + formatTime(remainingMinutes));
        }

        return switch (phase) {
            case LOBBY -> Text.literal("Lobby Phase - Waiting to start");
            case BUILD -> Text.literal("Build Phase - PvP disabled");
            case PVP -> Text.literal("PvP Phase - Battle!");
            case ENDED -> Text.literal("Game Ended");
            default -> Text.literal("Unknown Phase");
        };
    }

    /**
     * Gets the appropriate color for the BossBar based on the current game phase.
     * 
     * @return The BossBar.Color enum value.
     */
    private static BossBar.Color getBossBarColor() {
        return switch (phase) {
            case LOBBY -> BossBar.Color.WHITE;
            case BUILD -> BossBar.Color.GREEN;
            case PVP -> BossBar.Color.RED;
            case ENDED -> BossBar.Color.PURPLE;
            default -> BossBar.Color.WHITE;
        };
    }

    /**
     * Configures server-wide PvP settings and related game rules.
     * 
     * @param server  The Minecraft server instance.
     * @param enabled Whether PvP should be enabled.
     */
    private static void setPvp(MinecraftServer server, boolean enabled) {
        StormboundIslesMod.LOGGER.debug("Setting PvP enabled: {}", enabled);
        server.getOverworld().getGameRules().get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_MOB_SPAWNING).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_INSOMNIA).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_PATROL_SPAWNING).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_TRADER_SPAWNING).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_TILE_DROPS).set(true, server);
        server.getOverworld().getGameRules().get(GameRules.DO_ENTITY_DROPS).set(true, server);
        server.setPvpEnabled(enabled);
    }

    /**
     * Sets the game mode for all online players.
     * 
     * @param server The Minecraft server instance.
     * @param mode   The GameMode to set.
     */
    private static void setAllPlayersGameMode(MinecraftServer server, GameMode mode) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.interactionManager.getGameMode() != mode) {
                player.changeGameMode(mode);
            }
        }
    }

    /**
     * Finds a random, clear spawn position near the island's defined spawn point.
     * Attempts multiple random locations before falling back to the exact spawn
     * point.
     * 
     * @param island The island to spawn on.
     * @param world  The server world.
     * @return A suitable BlockPos for spawning.
     */
    private static BlockPos getRandomSpawnPosition(Island island, ServerWorld world) {
        // Try up to 10 random positions within 10-block radius
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist = random.nextDouble() * 10;
            int x = island.getSpawnX() + (int) Math.round(Math.cos(angle) * dist);
            int z = island.getSpawnZ() + (int) Math.round(Math.sin(angle) * dist);
            int y = island.getSpawnY();
            if (isAreaClear(world, x, y, z, 10)) {
                return new BlockPos(x + 1, y, z + 1);
            }
        }
        // Fallback to exact spawn
        return new BlockPos(island.getSpawnX() + 1, island.getSpawnY(), island.getSpawnZ() + 1);
    }

    /**
     * Checks if a cylindrical area around a center point is clear for spawning.
     * Verifies that the ground block is solid and the space above is air.
     * 
     * @param world  The server world.
     * @param cx     Center X coordinate.
     * @param cy     Center Y coordinate (spawn height).
     * @param cz     Center Z coordinate.
     * @param radius The radius of the area to check.
     * @return True if the area is clear, false otherwise.
     */
    private static boolean isAreaClear(ServerWorld world, int cx, int cy, int cz, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    BlockPos ground = new BlockPos(cx + dx, cy - 1, cz + dz);
                    BlockPos pos = new BlockPos(cx + dx, cy, cz + dz);
                    if (!world.getBlockState(ground).isSolidBlock(world, ground) ||
                            !world.getBlockState(pos).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Teleports all players belonging to teams to their assigned island's spawn
     * point.
     * Uses the custom spawn point if defined, otherwise logs an error.
     * 
     * @param server The Minecraft server instance.
     */
    private static void teleportPlayersToIslands(MinecraftServer server) {
        StormboundIslesMod.LOGGER.info("Teleporting players to their islands");

        for (Team team : DataManager.getTeams().values()) {
            if (team.getIslandId() == null) {
                StormboundIslesMod.LOGGER.warn("Team {} has no assigned island, skipping teleport", team.getName());
                continue;
            }

            Island island = DataManager.getIsland(team.getIslandId());
            if (island == null || island.getZone() == null) {
                StormboundIslesMod.LOGGER.warn("Island {} not found or has no defined zone", team.getIslandId());
                continue;
            }

            for (UUID uuid : team.getMembers()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    // Determine teleport target: use custom spawn if defined, else center of zone
                    BlockPos target;
                    if (island.getSpawnY() >= 0) {
                        target = getRandomSpawnPosition(island, server.getOverworld());
                    } else {
                        StormboundIslesMod.LOGGER.error(
                                "Island {} has no defined spawn position, unable to teleport player", island.getId());
                        // broadcast message to all players
                        server.getPlayerManager()
                                .broadcast(Text.literal("Island " + island.getId()
                                        + " has no defined spawn position, unable to teleport " + player.getName()),
                                        false);
                        // skip teleportation for this player
                        continue;
                    }
                    player.teleport(server.getOverworld(),
                            target.getX(), target.getY(), target.getZ(),
                            player.getYaw(), player.getPitch());
                }
            }
        }
    }

    /**
     * Handles per-tick game logic, including countdowns and phase transitions.
     * Updates the phase timer BossBar and persists game state periodically.
     * 
     * @param server The Minecraft server instance.
     */
    private static void onServerTick(MinecraftServer server) {
        // Handle pre-game countdown
        if (isStarting) {
            if (countdownTicks > 0) {
                countdownTicks--;
                // Update BossBar progress based on remaining countdown ticks
                if (phaseBar != null) {
                    float progress = (float) countdownTicks / ConfigManager.getGameCountdownDurationTicks();
                    phaseBar.setPercent(progress);
                    phaseBar.setName(Text.literal("Starting in " + (countdownTicks / 20 + 1) + "s"));
                }
                if (countdownTicks == 0) {
                    isStarting = false;
                    startGame(server);
                }
                return; // Don't process phase ticks during countdown
            }
        }

        if (phase == GamePhase.BUILD) {
            phaseTicks++;
            // Update bossbar progress
            if (phaseBar != null) {
                float progress = 1.0f - ((float) phaseTicks / ConfigManager.getGameBuildPhaseTicks());
                phaseBar.setPercent(progress);

                // Update title with remaining time every minute
                if (phaseTicks % (20 * 60) == 0) {
                    int remainingMinutes = (ConfigManager.getGameBuildPhaseTicks() - phaseTicks) / (20 * 60);
                    phaseBar.setName(Text.literal("Build Phase - " + formatTime(remainingMinutes)));
                    // Persist regularly
                    DataManager.saveGameState();
                }
            }

            if (phaseTicks >= ConfigManager.getGameBuildPhaseTicks()) {
                StormboundIslesMod.LOGGER.info("Build phase timer completed ({} ticks)",
                        ConfigManager.getGameBuildPhaseTicks());
                setPhase(GamePhase.PVP, server);
            }
        } else if (phase == GamePhase.PVP) {
            phaseTicks++;
            // Update bossbar progress
            if (phaseBar != null) {
                float progress = 1.0f - ((float) phaseTicks / ConfigManager.getGamePvpPhaseTicks());
                phaseBar.setPercent(progress);

                // Update title with remaining time every minute
                if (phaseTicks % (20 * 60) == 0) {
                    int remainingMinutes = (ConfigManager.getGamePvpPhaseTicks() - phaseTicks) / (20 * 60);
                    phaseBar.setName(Text.literal("PvP Phase - " + formatTime(remainingMinutes)));
                    // Persist regularly
                    DataManager.saveGameState();
                }
            }

            if (phaseTicks >= ConfigManager.getGamePvpPhaseTicks()) {
                StormboundIslesMod.LOGGER.info("PvP phase timer completed ({} ticks)",
                        ConfigManager.getGamePvpPhaseTicks());
                setPhase(GamePhase.ENDED, server);
                server.getPlayerManager().broadcast(Text.literal("Game ended!"), false);
            }
        }
    }

    /**
     * Formats a duration given in minutes into a human-readable string (e.g., "1h
     * 30m", "2d 5h 10m").
     * 
     * @param minutes The total number of minutes.
     * @return A formatted string representing the duration.
     */
    private static String formatTime(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }

        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;

        if (hours < 24) {
            return hours + "h " + remainingMinutes + "m";
        }

        int days = hours / 24;
        int remainingHours = hours % 24;

        return days + "d " + remainingHours + "h " + remainingMinutes + "m";
    }
}