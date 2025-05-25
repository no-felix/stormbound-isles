package de.nofelix.stormboundisles.util;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Contains constant values used throughout the mod.
 */
public class Constants {
	// Prevent instantiation
	private Constants() {
		throw new UnsupportedOperationException("Utility class");
	}

	/** Prefix used for mod-related messages. */
	public static final Text PREFIX = Text.literal("[Stormbound Isles] ").formatted(Formatting.BLUE);
	/**
	 * Message displayed when a player tries to execute a command without
	 * permission.
	 */
	public static final Text NO_PERMISSION = PREFIX.copy()
			.append(Text.literal("You don't have permission to do that!").formatted(Formatting.RED));
	/**
	 * Message displayed when a non-player entity tries to execute a player-only
	 * command.
	 */
	public static final Text PLAYER_ONLY = PREFIX.copy()
			.append(Text.literal("This command can only be executed by players!").formatted(Formatting.RED));
	/** Message displayed when a command sender provides invalid arguments. */
	public static final Text INVALID_ARGUMENTS = PREFIX.copy()
			.append(Text.literal("Invalid arguments!").formatted(Formatting.RED));
	/** Message displayed when a specified player cannot be found. */
	public static final Text PLAYER_NOT_FOUND = PREFIX.copy()
			.append(Text.literal("Player not found!").formatted(Formatting.RED));
	/** Message displayed when a specified team cannot be found. */
	public static final Text TEAM_NOT_FOUND = PREFIX.copy()
			.append(Text.literal("Team not found!").formatted(Formatting.RED));
	/** Formatted prefix for player-related information */
	public static final String PLAYER_PREFIX = "§6Player: §d";
	/** Formatted prefix for team-related information */
	public static final String TEAM_PREFIX = "§6Team: ";
	/** Formatted prefix for position-related information */
	public static final String POSITION_PREFIX = "§6Position: §e";
	/** Formatted prefix for island-related information */
	public static final String ISLAND_PREFIX = "§6Island: ";
	/** Formatted message for when a player is not on a team */
	public static final String NO_TEAM = "§7None";
	/** Formatted message for when a player is not in any island */
	public static final String NO_ISLAND = "§7None";
	/** Reset formatting code */
	public static final String RESET = "§r";

	// Scoreboard Constants
	/** Name of the main scoreboard objective for team points */
	public static final String SCOREBOARD_OBJECTIVE_NAME = "sbi_points";
	/** Display title for the scoreboard */
	public static final String SCOREBOARD_TITLE = "§b§lStormbound Isles";

	// Command Constants
	/**
	 * Reference to the configurable timeout duration for reset confirmation in
	 * milliseconds
	 */
	public static final String RESET_CONFIRMATION_MESSAGE = "⚠ WARNING: This will delete ALL game data. Run command again within 10 seconds to confirm.";
}
