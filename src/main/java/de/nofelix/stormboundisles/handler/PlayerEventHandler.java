package de.nofelix.stormboundisles.handler;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.game.ScoreboardManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles player-related events: death penalties and boundary enforcement
 * during the build phase.
 */
public final class PlayerEventHandler {
	private static int boundaryCheckCounter = 0;
	private static final Map<UUID, Long> lastBoundaryWarning = new HashMap<>();

	private PlayerEventHandler() {
	}

	/**
	 * Registers death and tick listeners.
	 */
	public static void register() {
		StormboundIslesMod.LOGGER.info("Registering PlayerEventHandler");
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, src) -> {
			if (entity instanceof ServerPlayerEntity player) {
				StormboundIslesMod.LOGGER.info(
						"Player {} died, handling death event.", player.getName().getString());
				handlePlayerDeath(player);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(PlayerEventHandler::onServerTick);
	}

	/**
	 * Called each server tick to enforce island boundaries in BUILD phase.
	 */
	private static void onServerTick(MinecraftServer server) {
		if (GameManager.phase != GamePhase.BUILD)
			return;

		if (++boundaryCheckCounter < ConfigManager.getPlayerBoundaryCheckInterval()) {
			return;
		}
		boundaryCheckCounter = 0;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			enforceIslandBoundary(player);
		}
	}

	/**
	 * Warns and teleports a player back if they leave their island during BUILD.
	 */
	private static void enforceIslandBoundary(ServerPlayerEntity player) {
		Optional<Team> team = DataManager.getTeams().values().stream()
				.filter(t -> t.getMembers().contains(player.getUuid()))
				.findFirst();

		if (team.isEmpty() || team.get().getIslandId() == null)
			return;

		Island island = DataManager.getIsland(team.get().getIslandId());
		if (island == null || island.getZone() == null)
			return;

		BlockPos pos = player.getBlockPos();
		if (!island.getZone().contains(pos)) {
			long now = System.currentTimeMillis();
			Long last = lastBoundaryWarning.get(player.getUuid());
			if (last == null || (now - last) > ConfigManager.getPlayerBoundaryWarningCooldownMs()) {
				player.sendMessage(
						Text.literal("§c⚠ You cannot leave your island during the build phase!"),
						true);
				lastBoundaryWarning.put(player.getUuid(), now);
			}

			if (island.getSpawnY() >= 0) {
				ServerWorld world = player.getServerWorld();
				player.teleport(
						world,
						island.getSpawnX() + 0.5, island.getSpawnY(), island.getSpawnZ() + 0.5,
						player.getYaw(), player.getPitch());
			}
		}
	}

	/**
	 * Applies point penalty on player death during BUILD or PVP phases.
	 */
	private static void handlePlayerDeath(ServerPlayerEntity player) {
		if (GameManager.phase != GamePhase.BUILD
				&& GameManager.phase != GamePhase.PVP) {
			return;
		}

		UUID id = player.getUuid();
		Optional<Team> team = DataManager.getTeams().values().stream()
				.filter(t -> t.getMembers().contains(id))
				.findFirst();

		team.ifPresent(t -> {
			int penalty = ConfigManager.getPlayerDeathPenalty();
			t.addPoints(-penalty);
			ScoreboardManager.updateTeamScore(t.getName());

			String msg = "Team " + t.getName() + " lost " + penalty +
					" points (Player death: " + player.getName().getString() + ")";
			StormboundIslesMod.LOGGER.info(msg);
			player.getServer()
					.getPlayerManager()
					.broadcast(Text.literal(msg), false);

			DataManager.saveAll();
			Island isl = DataManager.getIsland(t.getIslandId());

			// Only revive and force-teleport players during the BUILD phase.
			// In PVP phase (or other phases) we leave the normal death behavior intact
			// so players remain dead on hardcore servers.
			if (isl != null && isl.hasSpawnPoint() && GameManager.phase == GamePhase.BUILD) {
				ServerWorld world = player.getServerWorld();
				int tx = isl.getSpawnX();
				int ty = isl.getSpawnY();
				int tz = isl.getSpawnZ();

				player.teleport(world, tx + 0.5, ty, tz + 0.5, player.getYaw(), player.getPitch());

				// Revive
				try {
					player.setHealth(player.getMaxHealth());
				} catch (Throwable ex) {
					StormboundIslesMod.LOGGER.warn("Failed to set health for player {}: {}",
							player.getName().getString(), ex.getMessage());
				}

				// Safety
				player.teleport(world, tx + 0.5, ty, tz + 0.5, player.getYaw(), player.getPitch());
			}
		});
	}
}
