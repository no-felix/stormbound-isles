package de.nofelix.stormboundisles.handler;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.game.ScoreboardManager;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.Formatting;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.effect.StatusEffectInstance;

/**
 * Handles player-related events: death penalties and boundary enforcement
 * during the build phase.
 */
public final class PlayerEventHandler {
	private static int boundaryCheckCounter = 0;
	private static final Map<UUID, Long> lastBoundaryWarning = new HashMap<>();

	/**
	 * Private constructor to prevent instantiation.
	 */
	private PlayerEventHandler() {
		throw new UnsupportedOperationException("Utility class");
	}

	private static Formatting getTeamColor(Team team) {
		if (team == null)
			return Formatting.WHITE;
		return switch (team.getName().toUpperCase()) {
			case "PYROTHAR" -> Formatting.RED;
			case "FROSTREIGN" -> Formatting.AQUA;
			case "SAHRAKIR" -> Formatting.YELLOW;
			case "AURALIS" -> Formatting.LIGHT_PURPLE;
			default -> Formatting.WHITE;
		};
	}

	/**
	 * Registers death and tick listeners.
	 */
	@Initialize(priority = 1400, description = "Register player event handler")
	public static void register() {
		StormboundIslesMod.LOGGER.info("Registering PlayerEventHandler");
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, src) -> {
			if (entity instanceof ServerPlayerEntity player) {
				StormboundIslesMod.LOGGER.info(
						"Player {} died, handling death event.", player.getName().getString());
				handlePlayerDeath(player, src);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(PlayerEventHandler::onServerTick);

		// Clean up boundary warning timestamps when players disconnect to avoid
		// unbounded growth of the map.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler != null && handler.getPlayer() != null) {
				lastBoundaryWarning.remove(handler.getPlayer().getUuid());
			}
		});
	}

	/**
	 * Called each server tick to enforce island boundaries in BUILD phase.
	 */
	private static void onServerTick(MinecraftServer server) {
		if (GameManager.getPhase() != GamePhase.BUILD)
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

		ServerWorld world = player.getServerWorld();

		// Don't enforce boundaries in other dimensions
		if (world.getRegistryKey() != World.OVERWORLD) {
			StormboundIslesMod.LOGGER.debug("Skipping boundary enforcement for player {} in dimension {}",
					player.getName().getString(), world.getRegistryKey().getValue());
			return;
		}

		BlockPos pos = player.getBlockPos();
		if (!island.getZone().contains(pos)) {
			long now = System.currentTimeMillis();
			Long last = lastBoundaryWarning.get(player.getUuid());
			if (last == null || (now - last) > ConfigManager.getPlayerBoundaryWarningCooldownMs()) {
				player.sendMessage(Text.literal("§c⚠ You cannot leave your island during the build phase!"), true);
				lastBoundaryWarning.put(player.getUuid(), now);
				StormboundIslesMod.LOGGER.debug("Warning sent to player {} for leaving island {}",
						player.getName().getString(), team.get().getIslandId());
			}

			if (island.getSpawnY() >= 0) {

				double px = player.getX();
				double pz = player.getZ();
				double sx = island.getSpawnX() + 0.5;
				double sz = island.getSpawnZ() + 0.5;
				double dx = px - sx;
				double dz = pz - sz;
				double len = Math.sqrt(dx * dx + dz * dz);

				final double pushStep = ConfigManager.getPlayerBoundaryPushStep();
				final int maxSteps = ConfigManager.getPlayerBoundaryPushMaxSteps();
				boolean moved = false;

				if (len <= 0.0001) {
					double py = player.getY();
					player.teleport(world, sx, py, sz, player.getYaw(), player.getPitch());
					moved = true;
				} else {
					for (int i = 1; i <= maxSteps; i++) {
						double newLen = Math.max(0.0, len - pushStep * i);
						double nx = sx + (dx / len) * newLen;
						double nz = sz + (dz / len) * newLen;

						int cx = (int) Math.floor(nx);
						int cz = (int) Math.floor(nz);
						int cy = (int) Math.floor(player.getY());

						BlockPos candidate = new BlockPos(cx, cy, cz);
						if (island.getZone().contains(candidate)) {
							// Try small vertical adjustments before rejecting candidate
							double[] offsets = { 0.0, 1.0, -1.0, 2.0, -2.0 };
							boolean teleported = false;
							for (double off : offsets) {
								double tryY = player.getY() + off;
								if (isSafeTeleport(world, nx, tryY, nz)) {
									player.teleport(world, nx, tryY, nz, player.getYaw(), player.getPitch());
									StormboundIslesMod.LOGGER.debug("Pushed player {} back to {} (island {})",
											player.getName().getString(), new BlockPos(cx, (int) Math.floor(tryY), cz),
											team.get().getIslandId());
									moved = true;
									teleported = true;
									break;
								}
							}
							if (teleported)
								break;
						}
					}
				}

				if (!moved) {
					// Try fallback spawn teleport if it's safe
					// Try small vertical adjustments around spawn
					double[] offsets = { 0.0, 1.0, -1.0, 2.0, -2.0 };
					boolean teleported = false;
					for (double off : offsets) {
						double tryY = island.getSpawnY() + off;
						if (isSafeTeleport(world, sx, tryY, sz)) {
							player.teleport(world, sx, tryY, sz, player.getYaw(), player.getPitch());
							StormboundIslesMod.LOGGER.debug(
									"Fallback teleport of player {} to spawn of island {} at {}",
									player.getName().getString(), team.get().getIslandId(),
									new BlockPos((int) sx, (int) Math.floor(tryY), (int) sz));
							teleported = true;
							break;
						}
					}
					if (!teleported) {
						StormboundIslesMod.LOGGER.warn(
								"Could not safely teleport player {} to spawn of island {} (unsafe blocks at destination)",
								player.getName().getString(), team.get().getIslandId());
						player.sendMessage(Text.literal(
								"§cCould not safely teleport you back to your island. Please contact an admin."), true);
					}
				}
			}
		}
	}

	private static boolean isSafeTeleport(ServerWorld world, double x, double y, double z) {
		BlockPos target = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		BlockPos above = target.up();
		BlockPos below = target.down();

		// Destination and one block above must be air, block below must exist and not
		// be liquid
		return world.getBlockState(target).isAir() && world.getBlockState(above).isAir() &&
				!world.getBlockState(below).isAir() && world.getFluidState(below).isEmpty();
	}

	/**
	 * Applies point penalty on player death during BUILD or PVP phases.
	 */
	private static void handlePlayerDeath(ServerPlayerEntity player, DamageSource src) {
		GamePhase currentPhase = GameManager.getPhase();
		if (currentPhase != GamePhase.BUILD && currentPhase != GamePhase.PVP) {
			return;
		}

		UUID id = player.getUuid();
		Optional<Team> team = DataManager.getTeams().values().stream()
				.filter(t -> t.getMembers().contains(id))
				.findFirst();

		team.ifPresent(t -> {
			// record death for daily/phase tracking
			GameManager.recordPlayerDeath(id, t.getName());
			applyDeathPenalty(t, player);
			awardKillReward(t, player, src);
			reviveAndTeleportPlayer(t, player);
		});
	}

	/**
	 * Applies the configured death penalty to the victim's team and broadcasts it.
	 */
	private static void applyDeathPenalty(Team victimTeam, ServerPlayerEntity victim) {
		int penalty = ConfigManager.getPlayerDeathPenalty();
		victimTeam.addPoints(-penalty);
		ScoreboardManager.updateTeamScore(victimTeam.getName());

		// Format: Team <colored-name> lost <points> points (Player
		// death: <player>)
		String coloredTeam = getTeamColor(victimTeam) + "Team " + victimTeam.getName() + Formatting.RESET;
		String msg = coloredTeam + " lost §e" + penalty +
				" §cpoints §7(§ePlayer death: §f" + victim.getName().getString() + "§7)";
		StormboundIslesMod.LOGGER.info("Death broadcast: {}", msg);
		victim.getServer().getPlayerManager().broadcast(Text.literal(msg), false);

		DataManager.saveAll();
	}

	/**
	 * Awards the configurable kill reward to the killer's team if the killer was a
	 * player
	 * and is from a different island/team than the victim.
	 */
	private static void awardKillReward(Team victimTeam, ServerPlayerEntity victim, DamageSource src) {
		try {
			Optional<ServerPlayerEntity> maybeKiller = resolveKillerFromDamageSource(src, victim);
			if (maybeKiller.isEmpty())
				return;

			ServerPlayerEntity killer = maybeKiller.get();
			UUID kid = killer.getUuid();
			Optional<Team> kteam = DataManager.getTeams().values().stream()
					.filter(x -> x.getMembers().contains(kid))
					.findFirst();

			kteam.ifPresent(kt -> {
				// Only award if killer is from a different island/team
				if (kt.getIslandId() == null || !kt.getIslandId().equals(victimTeam.getIslandId())) {
					int reward = ConfigManager.getPlayerKillReward();
					kt.addPoints(reward);
					ScoreboardManager.updateTeamScore(kt.getName());
					// Format: Team <colored-name> gained <points> points (Kill:
					// <player>)
					String kcoloredTeam = getTeamColor(kt) + "Team " + kt.getName() + Formatting.RESET;
					String kmsg = kcoloredTeam + " gained §e" + reward +
							" §apoints §7(§eKill: §f" + killer.getName().getString() + "§7)";
					StormboundIslesMod.LOGGER.info("Kill broadcast: {}", kmsg);
					victim.getServer().getPlayerManager().broadcast(Text.literal(kmsg), false);
					DataManager.saveAll();
				}
			});
		} catch (Exception e) {
			StormboundIslesMod.LOGGER.warn("Failed to resolve owner via getOwner on attacker: {}", e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Revives and teleports the victim back to their bed spawn or island spawn
	 * during BUILD
	 * phase if configured.
	 */
	private static void reviveAndTeleportPlayer(Team victimTeam, ServerPlayerEntity victim) {
		if (GameManager.getPhase() != GamePhase.BUILD)
			return;

		ServerWorld world = victim.getServer().getWorld(World.OVERWORLD);
		if (world == null) {
			StormboundIslesMod.LOGGER.warn(
					"Overworld ServerWorld is not available, cannot teleport player {} to spawn.",
					victim.getName().getString());
			return;
		}

		// Determine target position: bed spawn if available and safe, else island spawn
		double targetX = 0.0;
		double targetY = 0.0;
		double targetZ = 0.0;
		boolean hasValidSpawn = false;

		// Try bed spawn first
		BlockPos bedPos = victim.getSpawnPointPosition();
		if (bedPos != null && victim.getSpawnPointDimension() == World.OVERWORLD &&
				world.getBlockState(bedPos).getBlock() instanceof BedBlock) {
			targetX = bedPos.getX() + 0.5;
			targetY = (double) bedPos.getY() + 1; // On top of the bed
			targetZ = bedPos.getZ() + 0.5;
			if (isSafeTeleport(world, targetX, targetY, targetZ)) {
				hasValidSpawn = true;
			} else {
				victim.sendMessage(
						Text.literal("§6⚠ Your bed spawn is occupied or unsafe! Using island spawn instead."), false);
				StormboundIslesMod.LOGGER.debug("Player {} bed spawn at {} is not safe, falling back to island spawn",
						victim.getName().getString(), bedPos);
			}
		}

		// Fall back to island spawn if bed not available or not safe
		if (!hasValidSpawn) {
			Island isl = DataManager.getIsland(victimTeam.getIslandId());
			if (isl == null || !isl.hasSpawnPoint())
				return;
			targetX = isl.getSpawnX() + 0.5;
			targetY = isl.getSpawnY();
			targetZ = isl.getSpawnZ() + 0.5;
		}

		// Teleport to spawn
		victim.teleport(world, targetX, targetY, targetZ, victim.getYaw(), victim.getPitch());

		// Revive (best effort)
		try {
			victim.setHealth(victim.getMaxHealth());
			victim.getHungerManager().setFoodLevel(20);
			victim.getHungerManager().setSaturationLevel(5.0f);
			victim.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 15 * 20, 1));
		} catch (Exception e) {
			StormboundIslesMod.LOGGER.warn("Failed to revive player {}: {}", victim.getName().getString(),
					e.getMessage());
			e.printStackTrace();
		}

		// Safety repeat teleport
		victim.teleport(world, targetX, targetY, targetZ, victim.getYaw(), victim.getPitch());
	}

	/**
	 * Try to call a no-arg getOwner() on the attacker and return a player owner if
	 * present.
	 */
	private static Optional<ServerPlayerEntity> getOwnerEntityViaGetOwner(Entity attacker) {
		if (attacker == null)
			return Optional.empty();
		try {
			Method m = attacker.getClass().getMethod("getOwner");
			Object owner = m.invoke(attacker);
			if (owner instanceof ServerPlayerEntity serverOwner)
				return Optional.of(serverOwner);
			if (owner instanceof Entity ent && ent instanceof ServerPlayerEntity serverOwner2)
				return Optional.of(serverOwner2);
		} catch (NoSuchMethodException | SecurityException ignored) {
			// Method not present on this attacker type, that's fine.
		} catch (Exception e) {
			StormboundIslesMod.LOGGER.debug("getOwner reflection failed: {}", e.getMessage());
		}
		return Optional.empty();
	}

	/**
	 * Try to call getOwnerUuid() on the attacker and resolve an online player by
	 * UUID.
	 */
	private static Optional<ServerPlayerEntity> getOwnerEntityViaGetOwnerUuid(Entity attacker,
			ServerPlayerEntity victim) {
		if (attacker == null || victim == null)
			return Optional.empty();
		try {
			Method m2 = attacker.getClass().getMethod("getOwnerUuid");
			Object val = m2.invoke(attacker);
			if (val instanceof UUID uuid) {
				ServerPlayerEntity p = victim.getServer().getPlayerManager().getPlayer(uuid);
				if (p != null)
					return Optional.of(p);
			} else if (val instanceof String s) {
				try {
					UUID uuid = UUID.fromString(s);
					ServerPlayerEntity p = victim.getServer().getPlayerManager().getPlayer(uuid);
					if (p != null)
						return Optional.of(p);
				} catch (IllegalArgumentException ignored) {
					// Not a UUID string
				}
			}
		} catch (NoSuchMethodException | SecurityException ignored) {
			// Method not present; ignore
		} catch (Exception e) {
			StormboundIslesMod.LOGGER.debug("getOwnerUuid reflection failed: {}", e.getMessage());
		}
		return Optional.empty();
	}

	/**
	 * Attempts to resolve the killer player from the provided DamageSource.
	 * Supports direct player attackers, projectiles with owners, and tameable
	 * entities
	 * exposing an owner UUID via common method names using reflection.
	 */
	private static Optional<ServerPlayerEntity> resolveKillerFromDamageSource(DamageSource src,
			ServerPlayerEntity victim) {
		if (src == null)
			return Optional.empty();

		Entity direct = src.getAttacker();
		if (direct instanceof ServerPlayerEntity serverPlayer)
			return Optional.of(serverPlayer);

		if (direct == null)
			return Optional.empty();

		// Try owner via getOwner()
		Optional<ServerPlayerEntity> maybe = getOwnerEntityViaGetOwner(direct);
		if (maybe.isPresent())
			return maybe;

		// Try owner via getOwnerUuid()
		return getOwnerEntityViaGetOwnerUuid(direct, victim);
	}
}
