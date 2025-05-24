package de.nofelix.stormboundisles;

import de.nofelix.stormboundisles.command.CommandManager;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.ScoreboardManager;
import de.nofelix.stormboundisles.handler.BuffAuraHandler;
import de.nofelix.stormboundisles.handler.PlayerEventHandler;
import de.nofelix.stormboundisles.init.InitializationRegistry;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Fabric mod class for Stormbound Isles.
 * Initializes config, data, commands, game systems, and event handlers.
 */
public final class StormboundIslesMod implements ModInitializer {
	public static final String MOD_ID = "stormboundisles";
	public static final String BASE_PACKAGE = "de.nofelix.stormboundisles";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private CommandManager commandManager;

	/**
	 * Called by the Fabric loader on mod initialization.
	 * Loads configuration and data, then registers commands and handlers.
	 */
	@Override
	public void onInitialize() {
		LOGGER.info("Stormbound Isles Mod initializing...");
		ConfigManager.loadConfig();
		DataManager.loadAll();

		// Run all initialization methods discovered by the annotation scanner
		InitializationRegistry.initializeAll(BASE_PACKAGE);

		// Initialize and register commands using the command manager
		commandManager = new CommandManager();
		commandManager.register();

		BuffAuraHandler.register();
		GameManager.register();
		ScoreboardManager.register();
		PlayerEventHandler.register();
		LOGGER.info("Stormbound Isles Mod initialized!");
	}
}