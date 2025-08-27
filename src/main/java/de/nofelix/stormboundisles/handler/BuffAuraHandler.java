package de.nofelix.stormboundisles.handler;

import de.nofelix.stormboundisles.StormboundIslesMod;
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
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

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

		long currentTime = server.getOverworld().getTimeOfDay();
		boolean shouldLog = currentTime - lastLogTime > LOG_INTERVAL;
		if (shouldLog) {
			StormboundIslesMod.LOGGER.debug("Checking player buffs");
			lastLogTime = currentTime;
		}

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			Optional<Team> optionalTeam = DataManager.getTeams().values().stream()
					.filter(t -> t.getMembers().contains(player.getUuid()))
					.findFirst();
			if (optionalTeam.isEmpty() || optionalTeam.get().getIslandId() == null) {
				continue;
			}

			Island island = DataManager.getIsland(optionalTeam.get().getIslandId());
			if (island == null || island.getZone() == null) {
				continue;
			}

			BlockPos pos = player.getBlockPos();
			if (island.getZone().contains(pos)) {
				if (shouldLog) {
					StormboundIslesMod.LOGGER.debug("Applying {} buff to {}", island.getType(),
							player.getName().getString());
				}
				applyBuff(player, island.getType());
			}
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
