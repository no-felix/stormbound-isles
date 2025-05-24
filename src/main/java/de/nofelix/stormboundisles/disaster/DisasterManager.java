package de.nofelix.stormboundisles.disaster;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.IslandType;
import de.nofelix.stormboundisles.game.ActionbarNotifier;
import de.nofelix.stormboundisles.init.Initialize;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the triggering and effects of random disasters on islands.
 * 
 * Disasters are selected based on island type and configured intervals,
 * applying specific effects to players within affected zones. The system
 * prevents duplicate disasters and automatically manages disaster lifecycles.
 * 
 * Example usage:
 * ```java
 * // Trigger a specific disaster
 * boolean triggered = DisasterManager.triggerDisaster(server, "island_01", DisasterType.METEOR);
 * 
 * // Cancel active disasters
 * boolean cancelled = DisasterManager.cancelActiveDisaster(server, "island_01");
 * ```
 */
public final class DisasterManager {

    // Constants
    private static final Logger LOGGER = StormboundIslesMod.LOGGER;
    private static final String DISASTER_KEY_SEPARATOR = ":";
    private static final long MILLISECONDS_PER_TICK = 50L;

    // Island type to disaster mappings
    private static final Map<IslandType, DisasterType[]> ISLAND_DISASTER_TYPES = Map.of(
            IslandType.VOLCANO, new DisasterType[] { DisasterType.METEOR },
            IslandType.ICE, new DisasterType[] { DisasterType.BLIZZARD },
            IslandType.DESERT, new DisasterType[] { DisasterType.SANDSTORM },
            IslandType.MUSHROOM, new DisasterType[] { DisasterType.SPORE },
            IslandType.CRYSTAL, new DisasterType[] { DisasterType.CRYSTAL_STORM });

    // Disaster effect implementations
    private static final Map<DisasterType, DisasterEffect> DISASTER_EFFECTS = Map.of(
            DisasterType.METEOR, DisasterManager::applyMeteorEffect,
            DisasterType.BLIZZARD, DisasterManager::applyBlizzardEffect,
            DisasterType.SANDSTORM, DisasterManager::applySandstormEffect,
            DisasterType.SPORE, DisasterManager::applySporeEffect,
            DisasterType.CRYSTAL_STORM, DisasterManager::applyCrystalStormEffect);

    // State management
    private static int tickCounter = 0;
    private static final Set<String> activeDisasters = new HashSet<>();
    private static final Object2LongMap<String> disasterExpirationTimes = new Object2LongOpenHashMap<>();

    private DisasterManager() {
    }

    // Initialization

    /**
     * Initializes the DisasterManager and registers server tick event listeners.
     * This method is automatically called during mod initialization.
     */
    @Initialize(priority = 1500)
    public static void initialize() {
        LOGGER.info("Initializing DisasterManager...");
        ServerTickEvents.END_SERVER_TICK.register(DisasterManager::onServerTick);
        LOGGER.info("DisasterManager initialized successfully");
    }

    // Public API methods

    /**
     * Triggers a specific disaster on a given island.
     * 
     * Applies effects to players currently within the island's zone and broadcasts
     * a server-wide message. Prevents triggering the same disaster type if it's
     * already active on the island.
     *
     * @param server   The Minecraft server instance
     * @param islandId The ID of the island where the disaster occurs
     * @param type     The type of disaster to trigger
     * @return true if disaster was triggered, false if the island doesn't exist or
     *         already has this disaster
     * @throws IllegalArgumentException if server, islandId, or type is null
     */
    public static boolean triggerDisaster(@NotNull MinecraftServer server, @NotNull String islandId,
            @NotNull DisasterType type) {
        validateTriggerParameters(server, islandId, type);

        Island island = DataManager.getIsland(islandId);
        if (island == null || island.getZone() == null) {
            LOGGER.debug("Cannot trigger disaster on island '{}': island or zone not found", islandId);
            return false;
        }

        String disasterKey = createDisasterKey(islandId, type);
        if (activeDisasters.contains(disasterKey)) {
            LOGGER.debug("Disaster {} already active on island '{}'", type, islandId);
            return false;
        }

        // Register the disaster as active with expiration time
        long expirationTime = calculateExpirationTime();
        activeDisasters.add(disasterKey);
        disasterExpirationTimes.put(disasterKey, expirationTime);

        LOGGER.info("Triggering disaster: {} on island: {}", type, islandId);

        // Broadcast and apply effects
        broadcastDisasterAlert(server, islandId, type);
        applyDisasterToPlayersOnIsland(server, island, type);

        return true;
    }

    /**
     * Cancels all active disasters on a specific island.
     * 
     * This method removes all active disasters associated with the given island ID
     * and notifies affected players.
     *
     * @param server   The Minecraft server instance
     * @param islandId The ID of the island where disasters should be cancelled
     * @return true if any disasters were cancelled, false if no active disasters
     *         were found
     * @throws IllegalArgumentException if server or islandId is null
     */
    public static boolean cancelActiveDisaster(@NotNull MinecraftServer server, @NotNull String islandId) {
        validateCancelParameters(server, islandId);

        Island island = DataManager.getIsland(islandId);
        if (island == null) {
            LOGGER.debug("Cannot cancel disaster on island '{}': island not found", islandId);
            return false;
        }

        Set<String> disastersToRemove = findActiveDisastersForIsland(islandId);
        if (disastersToRemove.isEmpty()) {
            LOGGER.debug("No active disasters found on island '{}'", islandId);
            return false;
        }

        // Remove all found disaster keys
        removeDisasters(disastersToRemove);

        // Notify players and broadcast
        notifyPlayersOfDisasterCancellation(server, island, islandId);
        broadcastDisasterCancellation(server, islandId);

        LOGGER.info("Cancelled {} disasters on island: {}", disastersToRemove.size(), islandId);
        return true;
    }

    /**
     * Gets the count of currently active disasters.
     * 
     * @return The number of active disasters across all islands
     */
    public static int getActiveDisasterCount() {
        return activeDisasters.size();
    }

    /**
     * Checks if a specific disaster is active on an island.
     * 
     * @param islandId The island ID to check
     * @param type     The disaster type to check
     * @return true if the disaster is active, false otherwise
     * @throws IllegalArgumentException if islandId or type is null
     */
    public static boolean isDisasterActive(@NotNull String islandId, @NotNull DisasterType type) {
        validateIslandIdAndType(islandId, type);
        return activeDisasters.contains(createDisasterKey(islandId, type));
    }

    // Private helper methods

    /**
     * Validates parameters for disaster triggering.
     */
    private static void validateTriggerParameters(MinecraftServer server,
            String islandId, DisasterType type) {
        if (server == null) {
            throw new IllegalArgumentException("Server cannot be null");
        }
        validateIslandIdAndType(islandId, type);
    }

    /**
     * Validates parameters for disaster cancellation.
     */
    private static void validateCancelParameters(MinecraftServer server, String islandId) {
        if (server == null) {
            throw new IllegalArgumentException("Server cannot be null");
        }
        if (islandId == null || islandId.trim().isEmpty()) {
            throw new IllegalArgumentException("Island ID cannot be null or empty");
        }
    }

    /**
     * Validates island ID and disaster type.
     */
    private static void validateIslandIdAndType(String islandId, DisasterType type) {
        if (islandId == null || islandId.trim().isEmpty()) {
            throw new IllegalArgumentException("Island ID cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Disaster type cannot be null");
        }
    }

    /**
     * Creates a unique key for tracking active disasters.
     */
    @NotNull
    private static String createDisasterKey(@NotNull String islandId, @NotNull DisasterType type) {
        return islandId + DISASTER_KEY_SEPARATOR + type;
    }

    /**
     * Calculates when a disaster should expire.
     */
    private static long calculateExpirationTime() {
        return System.currentTimeMillis() +
                (ConfigManager.getDisasterCooldownTicks() * MILLISECONDS_PER_TICK);
    }

    /**
     * Finds all active disasters for a specific island.
     */
    @NotNull
    private static Set<String> findActiveDisastersForIsland(@NotNull String islandId) {
        Set<String> disastersToRemove = new HashSet<>();
        String prefix = islandId + DISASTER_KEY_SEPARATOR;

        for (String key : activeDisasters) {
            if (key.startsWith(prefix)) {
                disastersToRemove.add(key);
            }
        }

        return disastersToRemove;
    }

    /**
     * Removes a set of disasters from active tracking.
     */
    private static void removeDisasters(@NotNull Set<String> disastersToRemove) {
        for (String key : disastersToRemove) {
            activeDisasters.remove(key);
            disasterExpirationTimes.removeLong(key);
        }
    }

    /**
     * Applies disaster effects to all players on an island.
     */
    private static void applyDisasterToPlayersOnIsland(@NotNull MinecraftServer server,
            @NotNull Island island, @NotNull DisasterType type) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (island.getZone().contains(player.getBlockPos())) {
                notifyPlayerOfDisaster(player, type);
                applyDisasterEffect(player, type, server);
            }
        }
    }

    /**
     * Notifies players on an island that disasters have been cancelled.
     */
    private static void notifyPlayersOfDisasterCancellation(@NotNull MinecraftServer server,
            @NotNull Island island, @NotNull String islandId) {
        if (island.getZone() == null) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (island.getZone().contains(player.getBlockPos())) {
                ActionbarNotifier.send(player, "§aDisaster on " + islandId + " has subsided!");
            }
        }
    }

    /**
     * Sends a disaster alert message to all players.
     */
    private static void broadcastDisasterAlert(@NotNull MinecraftServer server, @NotNull String islandId,
            @NotNull DisasterType type) {
        Text message = Text.literal("Disaster on " + islandId + ": " + type.name()).formatted(Formatting.RED);
        server.getPlayerManager().broadcast(message, false);
    }

    /**
     * Sends a disaster cancellation message to all players.
     */
    private static void broadcastDisasterCancellation(@NotNull MinecraftServer server, @NotNull String islandId) {
        Text message = Text.literal("The disaster on " + islandId + " has been cancelled.").formatted(Formatting.GREEN);
        server.getPlayerManager().broadcast(message, false);
    }

    /**
     * Notifies a player about an active disaster via action bar.
     */
    private static void notifyPlayerOfDisaster(@NotNull ServerPlayerEntity player, @NotNull DisasterType type) {
        ActionbarNotifier.send(player, "§cDisaster: " + type.name() + "!");
    }

    /**
     * Applies the specific effect of a disaster to a player.
     */
    private static void applyDisasterEffect(@NotNull ServerPlayerEntity player, @NotNull DisasterType type,
            @NotNull MinecraftServer server) {
        DisasterEffect effect = DISASTER_EFFECTS.get(type);
        if (effect != null) {
            effect.apply(player, server);
        } else {
            LOGGER.warn("No effect implementation found for disaster type: {}", type);
        }
    }

    // Disaster effect implementations

    private static void applyMeteorEffect(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server) {
        player.damage(server.getOverworld().getDamageSources().generic(),
                ConfigManager.getDisasterMeteorDamage());
    }

    private static void applyBlizzardEffect(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server) {
        player.setFrozenTicks(player.getFrozenTicks() + ConfigManager.getDisasterBlizzardFreezeTicks());
    }

    private static void applySandstormEffect(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server) {
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.BLINDNESS,
                ConfigManager.getDisasterEffectDurationTicks(),
                0));
    }

    private static void applySporeEffect(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server) {
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.POISON,
                ConfigManager.getDisasterEffectDurationTicks(),
                0));
    }

    private static void applyCrystalStormEffect(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server) {
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.LEVITATION,
                ConfigManager.getDisasterEffectDurationTicks(),
                0));
    }

    // Server tick handling

    /**
     * Called every server tick via the registered event listener.
     * Performs disaster lifecycle management and random disaster triggering.
     */
    private static void onServerTick(@NotNull MinecraftServer server) {
        // Check for expired disasters
        checkExpiredDisasters();

        // Periodic random disaster triggering
        tickCounter++;
        if (tickCounter >= ConfigManager.getDisasterIntervalTicks()) {
            tickCounter = 0;
            triggerRandomDisaster(server);
        }
    }

    /**
     * Removes any disasters that have passed their expiration time.
     */
    private static void checkExpiredDisasters() {
        long currentTime = System.currentTimeMillis();
        Iterator<Object2LongMap.Entry<String>> iterator = disasterExpirationTimes.object2LongEntrySet().iterator();

        while (iterator.hasNext()) {
            Object2LongMap.Entry<String> entry = iterator.next();
            if (currentTime > entry.getLongValue()) {
                String key = entry.getKey();
                activeDisasters.remove(key);
                iterator.remove(); // Safe removal during iteration

                LOGGER.debug("Disaster expired: {}", key);
            }
        }
    }

    /**
     * Selects a random island and disaster type, then triggers the disaster.
     */
    private static void triggerRandomDisaster(@NotNull MinecraftServer server) {
        List<Island> islands = new ArrayList<>(DataManager.getIslands().values());
        if (islands.isEmpty()) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Island island = islands.get(random.nextInt(islands.size()));

        // Skip islands without zones
        if (island.getZone() == null) {
            return;
        }

        // Determine possible disasters for the island type
        DisasterType[] possibleDisasters = ISLAND_DISASTER_TYPES.getOrDefault(
                island.getType(),
                new DisasterType[0]);

        if (possibleDisasters.length == 0) {
            return;
        }

        // Select and trigger a random disaster
        DisasterType disaster = possibleDisasters[random.nextInt(possibleDisasters.length)];
        triggerDisaster(server, island.getId(), disaster);
    }

    // Functional interface for disaster effects

    /**
     * Functional interface defining how disaster effects are applied to players.
     */
    @FunctionalInterface
    private interface DisasterEffect {
        /**
         * Apply a disaster effect to a player.
         * 
         * @param player The player to affect
         * @param server The server instance
         */
        void apply(@NotNull ServerPlayerEntity player, @NotNull MinecraftServer server);
    }
}