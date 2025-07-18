package de.nofelix.stormboundisles.handler;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.data.Zone;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.game.ScoreboardManager;
import de.nofelix.stormboundisles.util.Constants;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
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
	// Use centralized constants from Constants.java

	private PlayerEventHandler() {
	}

	/**
	 * Registers death and tick listeners.
	 */
	public static void register() {
		StormboundIslesMod.LOGGER.info("Registering PlayerEventHandler");
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, src) -> {
			if (entity instanceof ServerPlayerEntity player) {
				handlePlayerDeath(player);
				StormboundIslesMod.LOGGER.info(
						"Player {} died, handling death event.", player.getName().getString());
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

			// Calculate a safe position just inside the boundary
			BlockPos safePos = calculateSafePositionInsideBoundary(player.getBlockPos(), island.getZone());
			if (safePos != null) {
				ServerWorld world = player.getServerWorld();
				player.teleport(
						world,
						safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
						player.getYaw(), player.getPitch());
			} else if (island.getSpawnY() >= 0) {
				// Fallback to spawn point if safe position calculation fails
				StormboundIslesMod.LOGGER.warn("Safe boundary repositioning failed for player {}, teleporting to spawn.", player.getName().getString());
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
			if (isl != null && isl.hasSpawnPoint()) {
				player.teleport(
						player.getServerWorld(),
						isl.getSpawnX() + 0.5, isl.getSpawnY(), isl.getSpawnZ() + 0.5,
						player.getYaw(), player.getPitch());
			}
		});
	}

	/**
	 * Calculates a safe position just inside the boundary when a player is outside their zone.
	 * This method finds the nearest point inside the zone boundary and moves the player there
	 * instead of teleporting them back to the spawn point.
	 *
	 * @param playerPos The current position of the player (outside the boundary)
	 * @param zone The island zone boundary
	 * @return A safe BlockPos inside the boundary, or null if calculation fails
	 */
	private static BlockPos calculateSafePositionInsideBoundary(BlockPos playerPos, Zone zone) {
		List<BlockPos> zonePoints = zone.getPoints();
		if (zonePoints.isEmpty()) {
			return null;
		}

		// Find the center of the zone as a reference point inside the boundary
		double centerX = zonePoints.stream().mapToInt(BlockPos::getX).average().orElse(0);
		double centerZ = zonePoints.stream().mapToInt(BlockPos::getZ).average().orElse(0);
		BlockPos center = new BlockPos((int) Math.round(centerX), playerPos.getY(), (int) Math.round(centerZ));

		// If center is not inside the zone (for complex shapes), find a point that is
		if (!zone.contains(center)) {
			center = findPointInsideZone(zone, playerPos.getY());
			if (center == null) {
				return null;
			}
		}

		// Calculate direction from player to center
		double dirX = (double) center.getX() - playerPos.getX();
		double dirZ = (double) center.getZ() - playerPos.getZ();
		double distance = Math.sqrt(dirX * dirX + dirZ * dirZ);

		if (distance == 0) {
			return center; // Player is at center, just return center
		}

		// Normalize direction vector
		dirX /= distance;
		dirZ /= distance;

		// Move player towards center until we find a position inside the boundary
		// Start with a small step and increase if needed
		for (int step = 1; step <= Constants.MAX_REPOSITION_STEPS; step++) {
			int newX = (int) Math.round(playerPos.getX() + dirX * step);
			int newZ = (int) Math.round(playerPos.getZ() + dirZ * step);
			BlockPos candidatePos = new BlockPos(newX, playerPos.getY(), newZ);

			if (zone.contains(candidatePos)) {
				return candidatePos;
			}
		}

		// If the simple approach failed, try to find the closest point on the boundary
		return findClosestPointInsideBoundary(playerPos, zone);
	}

	/**
	 * Finds any point inside the zone using the first vertex as a starting point.
	 * For complex polygons where the center might be outside.
	 */
	private static BlockPos findPointInsideZone(Zone zone, int y) {
		List<BlockPos> points = zone.getPoints();
		if (points.size() < 3) {
			return null;
		}

		// For a triangle or simple polygon, try the centroid of first 3 points
		BlockPos p1 = points.get(0);
		BlockPos p2 = points.get(1);
		BlockPos p3 = points.get(2);

		int centroidX = (p1.getX() + p2.getX() + p3.getX()) / 3;
		int centroidZ = (p1.getZ() + p2.getZ() + p3.getZ()) / 3;
		BlockPos centroid = new BlockPos(centroidX, y, centroidZ);

		if (zone.contains(centroid)) {
			return centroid;
		}

		// If centroid fails, try moving slightly inward from the first edge
		int midX = (p1.getX() + p2.getX()) / 2;
		int midZ = (p1.getZ() + p2.getZ()) / 2;

		// Calculate perpendicular direction inward (simplified approach)
		int perpX = -(p2.getZ() - p1.getZ());
		int perpZ = p2.getX() - p1.getX();

		// Try a few positions moving inward
		for (int i = 1; i <= de.nofelix.stormboundisles.util.Constants.MAX_INWARD_ATTEMPTS; i++) {
			int testX = midX + (perpX > 0 ? i : -i);
			int testZ = midZ + (perpZ > 0 ? i : -i);
			BlockPos testPos = new BlockPos(testX, y, testZ);

			if (zone.contains(testPos)) {
				return testPos;
			}
		}

		return null;
	}

	/**
	 * Finds the closest valid position inside the boundary using a more sophisticated approach.
	 */
	private static BlockPos findClosestPointInsideBoundary(BlockPos playerPos, Zone zone) {
		BlockPos closestPos = null;
		double closestDistance = Double.MAX_VALUE;

		for (int dx = -de.nofelix.stormboundisles.util.Constants.SEARCH_RADIUS; dx <= de.nofelix.stormboundisles.util.Constants.SEARCH_RADIUS; dx++) {
			for (int dz = -de.nofelix.stormboundisles.util.Constants.SEARCH_RADIUS; dz <= de.nofelix.stormboundisles.util.Constants.SEARCH_RADIUS; dz++) {
				BlockPos candidatePos = new BlockPos(
					playerPos.getX() + dx,
					playerPos.getY(),
					playerPos.getZ() + dz
				);

				if (zone.contains(candidatePos)) {
					double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
					if (distance < closestDistance) {
						closestDistance = distance;
						closestPos = candidatePos;
					}
				}
			}
		}

		return closestPos;
	}
}
