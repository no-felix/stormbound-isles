package de.nofelix.stormboundisles.game;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.init.Initialize;
import de.nofelix.stormboundisles.util.Constants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages the scoreboard display and team assignments for the Stormbound Isles
 * mod.
 * <p>
 * This manager handles:
 * <ul>
 * <li>Scoreboard objectives for team points display</li>
 * <li>Synchronization between Minecraft scoreboard teams and DataManager
 * teams</li>
 * <li>Real-time score updates and team assignments</li>
 * <li>Player join/leave team management</li>
 * </ul>
 * 
 * @see DataManager
 * @see Team
 */
public final class ScoreboardManager {
	// Static state
	private static Scoreboard scoreboard;
	private static ScoreboardObjective objective;
	private static MinecraftServer currentServer;

	/**
	 * Private constructor to prevent instantiation.
	 */
	private ScoreboardManager() {
		throw new UnsupportedOperationException("Utility class");
	}

	/**
	 * Initializes the scoreboard management system.
	 * <p>
	 * Sets up event listeners for server lifecycle, player connections,
	 * and periodic score updates.
	 * 
	 * @see Initialize
	 */
	@Initialize(priority = 1500, description = "Initialize scoreboard management system")
	public static void initialize() {
		registerEventListeners();
		StormboundIslesMod.LOGGER.info("ScoreboardManager initialized successfully.");
	}

	/**
	 * Registers all necessary event listeners for scoreboard management.
	 */
	private static void registerEventListeners() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			currentServer = server;
			initializeScoreboard(server);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (currentServer == null) {
				currentServer = server;
			}

			if (server.getTicks() % ConfigManager.getScoreboardUpdateInterval() == 0) {
				if (isScoreboardReady()) {
					updateAllScores();
				} else if (currentServer != null) {
					StormboundIslesMod.LOGGER.warn("Scoreboard not ready, attempting re-initialization.");
					initializeScoreboard(currentServer);
				}
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			currentServer = server;
			ServerPlayerEntity player = handler.player;
			assignPlayerToTeam(player);

			// Ensure the sidebar objective is set on the server scoreboard so it syncs to
			// the joining client
			try {
				if (objective != null) {
					int sidebarId = Scoreboard.getDisplaySlotId("sidebar");
					server.getScoreboard().setObjectiveSlot(sidebarId, objective);

					// The scoreboard will automatically sync to the client when they join
					// No need to send explicit packets which can cause "already exists" errors
				}
			} catch (Exception e) {
				StormboundIslesMod.LOGGER.warn("Failed to sync scoreboard objective to joining player: {}",
						e.toString());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			currentServer = server;
			removePlayerFromTeams(handler.player);
		});

		// No need for retry mechanism since we're not sending explicit packets
		StormboundIslesMod.LOGGER.info("ScoreboardManager event listeners registered.");
	}

	/**
	 * Initializes the main scoreboard and objective.
	 * 
	 * @param server The Minecraft server instance
	 */
	private static void initializeScoreboard(MinecraftServer server) {
		if (server == null) {
			StormboundIslesMod.LOGGER.error("Cannot initialize scoreboard: Server instance is null.");
			return;
		}

		currentServer = server;
		scoreboard = server.getScoreboard();

		if (scoreboard == null) {
			StormboundIslesMod.LOGGER.error("Cannot initialize scoreboard: Server scoreboard is null.");
			return;
		}

		StormboundIslesMod.LOGGER.info("Initializing scoreboard...");

		setupObjective();
		setupTeamProperties();
		updateAllScores();
		assignOnlinePlayersToTeams(server);

		StormboundIslesMod.LOGGER.info("Scoreboard initialized successfully.");
	}

	/**
	 * Sets up the main scoreboard objective for team points.
	 */
	private static void setupObjective() {
		// Remove existing objective if present
		Optional.ofNullable(scoreboard.getNullableObjective(Constants.SCOREBOARD_OBJECTIVE_NAME))
				.ifPresent(existing -> {
					scoreboard.removeObjective(existing);
					StormboundIslesMod.LOGGER.debug("Removed existing scoreboard objective: {}",
							Constants.SCOREBOARD_OBJECTIVE_NAME);
				});

		try {
			objective = scoreboard.addObjective(
					Constants.SCOREBOARD_OBJECTIVE_NAME,
					ScoreboardCriterion.DUMMY,
					Text.literal(Constants.SCOREBOARD_TITLE),
					ScoreboardCriterion.RenderType.INTEGER);

			// Ensure the objective is assigned to the sidebar display slot so the client
			// HUD will render it
			try {
				int sidebarId = Scoreboard.getDisplaySlotId("sidebar");
				scoreboard.setObjectiveSlot(sidebarId, objective);
			} catch (Exception e) {
				StormboundIslesMod.LOGGER.warn("Could not set objective to sidebar display slot: {}", e.toString());
			}

			StormboundIslesMod.LOGGER.debug("Created and configured scoreboard objective: {}",
					Constants.SCOREBOARD_OBJECTIVE_NAME);
		} catch (IllegalArgumentException e) {
			objective = scoreboard.getNullableObjective(Constants.SCOREBOARD_OBJECTIVE_NAME);
			if (objective == null) {
				StormboundIslesMod.LOGGER.error("Failed to create scoreboard objective: {}",
						Constants.SCOREBOARD_OBJECTIVE_NAME, e);
			} else {
				StormboundIslesMod.LOGGER.warn("Scoreboard objective {} already existed.",
						Constants.SCOREBOARD_OBJECTIVE_NAME);
			}
		}
	}

	/**
	 * Utility methods for scoreboard management
	 */

	/**
	 * Checks if the scoreboard and objective are properly initialized.
	 * 
	 * @return true if both scoreboard and objective are ready
	 */
	private static boolean isScoreboardReady() {
		return scoreboard != null && objective != null;
	}

	/**
	 * Assigns all currently online players to their appropriate teams.
	 * 
	 * @param server The Minecraft server instance
	 */
	private static void assignOnlinePlayersToTeams(MinecraftServer server) {
		StormboundIslesMod.LOGGER.debug("Assigning online players to scoreboard teams...");
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			assignPlayerToTeam(player);
		}
	}

	/**
	 * Score management methods
	 */

	/**
	 * Updates all team scores on the scoreboard.
	 */
	public static void updateAllScores() {
		if (!isScoreboardReady()) {
			StormboundIslesMod.LOGGER.warn(
					"Cannot update scores: Scoreboard not ready. Re-initialization will be attempted by the tick handler.");
			return;
		}

		DataManager.getTeams().values().forEach(team -> {
			String displayName = getDisplayNameForTeam(team);
			ScoreboardPlayerScore score = scoreboard.getPlayerScore(displayName, objective);
			if (score != null) {
				score.setScore(team.getPoints());
			} else {
				StormboundIslesMod.LOGGER.warn("Could not get or create score for display name: {}", displayName);
			}
		});
	}

	/**
	 * Updates the score for a specific team.
	 * 
	 * @param teamName The name of the team to update
	 */
	public static void updateTeamScore(String teamName) {
		if (!isScoreboardReady()) {
			StormboundIslesMod.LOGGER.warn("Cannot update team score: Scoreboard not ready.");
			return;
		}

		Optional.ofNullable(DataManager.getTeam(teamName))
				.ifPresentOrElse(
						team -> {
							String displayName = getDisplayNameForTeam(team);
							ScoreboardPlayerScore score = scoreboard.getPlayerScore(displayName,
									objective);
							if (score != null) {
								score.setScore(team.getPoints());
							} else {
								StormboundIslesMod.LOGGER.warn("Could not get or create score for display name: {}",
										displayName);
							}
						},
						() -> StormboundIslesMod.LOGGER.warn("Cannot update score for non-existent team: {}",
								teamName));
	}

	/**
	 * Team management methods
	 */

	/**
	 * Sets up scoreboard team properties based on DataManager teams.
	 */
	private static void setupTeamProperties() {
		if (!isScoreboardReady()) {
			StormboundIslesMod.LOGGER.warn("Cannot setup team properties: Scoreboard not ready.");
			return;
		}

		StormboundIslesMod.LOGGER.debug("Setting up scoreboard team properties...");

		DataManager.getTeams().forEach((teamName, teamData) -> {
			net.minecraft.scoreboard.Team sbTeam = getOrCreateScoreboardTeam(teamName);
			configureTeamProperties(sbTeam, teamData);
		});

		StormboundIslesMod.LOGGER.debug("Finished setting up scoreboard team properties.");
	}

	/**
	 * Gets or creates a scoreboard team.
	 * 
	 * @param teamName The name of the team
	 * @return The scoreboard team, or null if creation failed
	 */
	private static net.minecraft.scoreboard.Team getOrCreateScoreboardTeam(String teamName) {
		net.minecraft.scoreboard.Team sbTeam = scoreboard.getTeam(teamName);
		if (sbTeam == null) {
			sbTeam = scoreboard.addTeam(teamName);
			StormboundIslesMod.LOGGER.debug("Created scoreboard team: {}", teamName);
		} else {
			StormboundIslesMod.LOGGER.debug("Using existing scoreboard team: {}", teamName);
		}
		return sbTeam;
	}

	/**
	 * Configures properties for a scoreboard team.
	 * 
	 * @param sbTeam   The scoreboard team to configure
	 * @param teamData The team data from DataManager
	 */
	private static void configureTeamProperties(net.minecraft.scoreboard.Team sbTeam, Team teamData) {
		if (sbTeam == null || teamData == null) {
			return;
		}

		Formatting color = getTeamColor(teamData);
		sbTeam.setDisplayName(Text.literal(teamData.getName()));
		sbTeam.setColor(color);
		sbTeam.setPrefix(Text.literal("[")
				.append(Text.literal(teamData.getName()).formatted(color))
				.append("] "));
		sbTeam.setFriendlyFireAllowed(false);
		sbTeam.setShowFriendlyInvisibles(true);
	}

	/**
	 * Player assignment methods
	 */

	/**
	 * Assigns a player to their appropriate scoreboard team based on DataManager
	 * data.
	 * 
	 * @param player The player to assign
	 */
	private static void assignPlayerToTeam(ServerPlayerEntity player) {
		if (!isScoreboardReady() || player == null) {
			StormboundIslesMod.LOGGER.warn("Cannot assign player to team: Invalid state or null player.");
			return;
		}

		String playerName = player.getGameProfile().getName();
		Optional<Team> playerTeam = findPlayerTeam(player.getUuid());

		if (playerTeam.isPresent()) {
			assignPlayerToScoreboardTeam(playerName, playerTeam.get().getName());
		} else {
			StormboundIslesMod.LOGGER.debug(
					"Player {} not found in any team. Removing from all scoreboard teams.", playerName);
			removePlayerFromAllTeams(playerName);
		}
	}

	/**
	 * Assigns a player to a specific scoreboard team.
	 * 
	 * @param playerName The player's name
	 * @param teamName   The team name
	 */
	private static void assignPlayerToScoreboardTeam(String playerName, String teamName) {
		net.minecraft.scoreboard.Team sbTeam = scoreboard.getTeam(teamName);

		if (sbTeam == null) {
			StormboundIslesMod.LOGGER.warn(
					"Scoreboard team {} not found for player {}. Re-running setup.", teamName, playerName);
			setupTeamProperties();
			sbTeam = scoreboard.getTeam(teamName);
		}

		if (sbTeam != null) {
			if (!sbTeam.getPlayerList().contains(playerName)) {
				scoreboard.addPlayerToTeam(playerName, sbTeam);
				StormboundIslesMod.LOGGER.info("Added player {} to scoreboard team {}", playerName, teamName);
			} else {
				StormboundIslesMod.LOGGER.debug("Player {} already in scoreboard team {}", playerName, teamName);
			}
		} else {
			StormboundIslesMod.LOGGER.error("Failed to find/create scoreboard team {} even after re-setup.", teamName);
		}
	}

	/**
	 * Removes a player from scoreboard teams when they disconnect.
	 * 
	 * @param player The disconnecting player
	 */
	private static void removePlayerFromTeams(ServerPlayerEntity player) {
		if (!isScoreboardReady() || player == null) {
			return;
		}
		removePlayerFromAllTeams(player.getGameProfile().getName());
	}

	/**
	 * Removes a player from all scoreboard teams.
	 * 
	 * @param playerName The player's name
	 */
	private static void removePlayerFromAllTeams(String playerName) {
		if (!isScoreboardReady()) {
			return;
		}

		// Remove from DataManager teams
		DataManager.getTeams().keySet().forEach(teamName -> {
			net.minecraft.scoreboard.Team sbTeam = scoreboard.getTeam(teamName);
			if (sbTeam != null && sbTeam.getPlayerList().contains(playerName)) {
				scoreboard.removePlayerFromTeam(playerName, sbTeam);
				StormboundIslesMod.LOGGER.info("Removed player {} from scoreboard team {}", playerName, teamName);
			}
		});

		// Remove from non-DataManager teams
		AbstractTeam playerTeam = scoreboard.getPlayerTeam(playerName);
		if (playerTeam instanceof net.minecraft.scoreboard.Team team &&
				DataManager.getTeam(playerTeam.getName()) == null) {
			scoreboard.removePlayerFromTeam(playerName, team);
			StormboundIslesMod.LOGGER.info("Removed player {} from non-DataManager scoreboard team {}.",
					playerName, playerTeam.getName());
		}
	}

	/**
	 * Public API for external team updates
	 */

	/**
	 * Updates all team assignments and properties for currently online players.
	 * This method should be called when team configurations change.
	 * 
	 * @param server The Minecraft server instance
	 */
	public static void updateAllTeams(MinecraftServer server) {
		if (server == null) {
			StormboundIslesMod.LOGGER.error("Cannot update all teams: Server instance is null.");
			return;
		}

		currentServer = server;
		if (!isScoreboardReady()) {
			StormboundIslesMod.LOGGER.warn("Scoreboard not ready during updateAllTeams. Attempting initialization.");
			initializeScoreboard(server);
			if (!isScoreboardReady()) {
				return;
			}
		}

		StormboundIslesMod.LOGGER.info("Refreshing all scoreboard team properties and online player assignments...");

		setupTeamProperties();
		reassignAllOnlinePlayers(server);

		StormboundIslesMod.LOGGER.info("Finished refreshing scoreboard teams.");
	}

	/**
	 * Reassigns all online players to their correct teams.
	 * 
	 * @param server The Minecraft server instance
	 */
	private static void reassignAllOnlinePlayers(MinecraftServer server) {
		server.getPlayerManager().getPlayerList().forEach(player -> {
			String playerName = player.getGameProfile().getName();
			AbstractTeam currentSbTeam = scoreboard.getPlayerTeam(playerName);
			Optional<Team> dataManagerTeam = findPlayerTeam(player.getUuid());

			// Remove from incorrect team
			if (currentSbTeam instanceof net.minecraft.scoreboard.Team team &&
					(dataManagerTeam.isEmpty() || !currentSbTeam.getName().equals(dataManagerTeam.get().getName()))) {
				scoreboard.removePlayerFromTeam(playerName, team);
				StormboundIslesMod.LOGGER.debug("Removed player {} from incorrect scoreboard team {} during refresh.",
						playerName, currentSbTeam.getName());
			}

			// Assign to correct team
			assignPlayerToTeam(player);
		});
	}

	/**
	 * Utility methods
	 */

	/**
	 * Finds the team that a player belongs to in DataManager.
	 * 
	 * @param playerUuid The player's UUID
	 * @return Optional containing the player's team, or empty if not found
	 */
	private static Optional<Team> findPlayerTeam(UUID playerUuid) {
		return DataManager.getTeams().values().stream()
				.filter(team -> team.getMembers().contains(playerUuid))
				.findFirst();
	}

	/**
	 * Generates a display name for a team with color formatting.
	 * 
	 * @param team The team to generate a display name for
	 * @return Formatted display name
	 */
	public static String getDisplayNameForTeam(Team team) {
		return getTeamColor(team) + team.getName();
	}

	/**
	 * Gets the color formatting for a team based on its name.
	 * 
	 * @param team The team to get color for
	 * @return The formatting color for the team
	 */
	public static Formatting getTeamColor(Team team) {
		return switch (team.getName().toUpperCase()) {
			case "PYROTHAR" -> Formatting.RED;
			case "FROSTREIGN" -> Formatting.AQUA;
			case "SAHRAKIR" -> Formatting.YELLOW;
			case "AURALIS" -> Formatting.LIGHT_PURPLE;
			default -> Formatting.WHITE;
		};
	}
}
