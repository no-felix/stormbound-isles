package de.nofelix.stormboundisles.handler;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.util.AsyncOperationManager;
import de.nofelix.stormboundisles.util.ZoneChecker;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.IslandType;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Applies island‑specific buffs to players within their island zones at
 * configured intervals.
 */
public class BuffAuraHandler {
	private static final long LOG_INTERVAL = 10_000L;
	private static final boolean AMBIENT = false;
	private static final boolean SHOW_PARTICLES = true;

	private static long lastLogTime = 0L;
	private static int tickCounter = 0;

	/**
	 * Private constructor to prevent instantiation.
	 */
	private BuffAuraHandler() {
		throw new UnsupportedOperationException("Utility class");
	}

	/**
	 * Registers the server tick listener for buff application.
	 */
	@Initialize(priority = 1400, description = "Register buff aura handler")
	public static void register() {
		StormboundIslesMod.LOGGER.info("Registering BuffAuraHandler");
		ServerTickEvents.END_SERVER_TICK.register(BuffAuraHandler::onServerTick);
	}

	/**
	 * Called each server tick; applies buffs when the configured interval has
	 * elapsed.
	 *
	 * @param server the running Minecraft server
	 */
	private static void onServerTick(MinecraftServer server) {
		if (++tickCounter < ConfigManager.getBuffUpdateInterval()) {
			return;
		}
		tickCounter = 0;

		List<ServerPlayerEntity> onlinePlayers = server.getPlayerManager().getPlayerList();
		if (onlinePlayers.isEmpty()) {
			return;
		}

		processBuffsForOccupiedIslands(server, onlinePlayers);

		// Periodic ZoneChecker cache cleanup to prevent memory leaks
		ZoneChecker.cleanupCache(onlinePlayers);
	}

	/**
	 * Processes buffs only for islands that have players on them.
	 * Major performance improvement by skipping empty islands.
	 */
	private static void processBuffsForOccupiedIslands(MinecraftServer server, List<ServerPlayerEntity> onlinePlayers) {
		long currentTime = server.getOverworld().getTimeOfDay();
		boolean shouldLog = currentTime - lastLogTime > LOG_INTERVAL;
		if (shouldLog) {
			StormboundIslesMod.LOGGER.debug("Checking player buffs");
			lastLogTime = currentTime;
		}

		// First pass: collect islands that have players (avoids duplicate zone checks)
		Set<String> occupiedIslandIds = new HashSet<>();
		for (ServerPlayerEntity player : onlinePlayers) {
			String islandId = ZoneChecker.findPlayerIsland(player, DataManager.getIslands());
			if (islandId != null) {
				occupiedIslandIds.add(islandId);
			}
		}

		// Second pass: process only occupied islands
		if (shouldLog) {
			StormboundIslesMod.LOGGER.debug("Processing buffs for {} occupied islands (skipped {})",
				occupiedIslandIds.size(), DataManager.getIslands().size() - occupiedIslandIds.size());
		}

		for (String islandId : occupiedIslandIds) {
			Island island = DataManager.getIsland(islandId);
			if (island != null && island.getZone() != null) {
				processBuffsForSingleIsland(island, onlinePlayers, shouldLog);
			}
		}
	}

	/**
	 * Processes buffs for a single island if it has players on it.
	 * Uses async processing for expensive zone containment checks.
	 */
	private static void processBuffsForSingleIsland(Island island, List<ServerPlayerEntity> onlinePlayers,
			boolean shouldLog) {
		// Use async operation for expensive zone containment checks
		AsyncOperationManager.submitAsync(
				() -> ZoneChecker.getPlayersInZone(
					island.getId(), island.getZone(), onlinePlayers),
				playersOnIsland -> {
					// This callback runs on main thread - safe to apply buffs
					if (playersOnIsland.isEmpty()) {
						return;
					}

					for (ServerPlayerEntity player : playersOnIsland) {
						applyBuffIfPlayerOwnsIsland(player, island, shouldLog);
					}
				},
				error -> StormboundIslesMod.LOGGER.error("Error processing buffs for island {}", island.getId(),
						error));
	}

	/**
	 * Applies buff to a player if they are on their team's island.
	 */
	private static void applyBuffIfPlayerOwnsIsland(ServerPlayerEntity player, Island island, boolean shouldLog) {
		Optional<Team> optionalTeam = DataManager.getTeams().values().stream()
				.filter(t -> t.getMembers().contains(player.getUuid()))
				.findFirst();

		if (optionalTeam.isPresent() &&
				island.getId().equals(optionalTeam.get().getIslandId())) {
			if (shouldLog) {
				StormboundIslesMod.LOGGER.debug("Applying {} buff to {}", island.getType(),
						player.getName().getString());
			}
			applyBuff(player, island.getType());
		}
	}

	/**
	 * Applies the status effect corresponding to the given island type.
	 *
	 * @param player the target player
	 * @param type   the island type determining the buff
	 */
	private static void applyBuff(ServerPlayerEntity player, IslandType type) {
		int duration = ConfigManager.getBuffDurationTicks();
		StatusEffectInstance effect = switch (type) {
			case SAHRAKIR ->
				new StatusEffectInstance(StatusEffects.SPEED, duration, 0, true, AMBIENT, SHOW_PARTICLES);
			case PYROTHAR ->
				new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, duration, 0, true, AMBIENT, SHOW_PARTICLES);
			case FROSTREIGN ->
				new StatusEffectInstance(StatusEffects.RESISTANCE, duration, 0, true, AMBIENT, SHOW_PARTICLES);
			case AURALIS ->
				new StatusEffectInstance(StatusEffects.REGENERATION, duration, 0, true, AMBIENT, SHOW_PARTICLES);
		};
		player.addStatusEffect(effect);
	}
}
