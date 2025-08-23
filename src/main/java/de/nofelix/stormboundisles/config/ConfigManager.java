package de.nofelix.stormboundisles.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages loading, accessing, and saving the mod's configuration settings.
 * 
 * Configuration is loaded from `stormbound-isles-config.json` in the config
 * directory. If the file doesn't exist, default values are used and a new
 * file is created.
 * 
 * The configuration system provides automatic validation and correction of
 * invalid values, ensuring the mod remains stable even with corrupted configs.
 * 
 * Example usage:
 * ```java
 * int buildPhase = ConfigManager.getGameBuildPhaseTicks();
 * int deathPenalty = ConfigManager.getPlayerDeathPenalty();
 * ```
 */
public final class ConfigManager {

    private static final String CONFIG_FILENAME = "stormbound-isles-config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = StormboundIslesMod.LOGGER;

    // Default values as constants
    private static final class Defaults {
        static final int BUILD_PHASE_TICKS = 20 * 60 * 60 * 24 * 7; // 1 week
        static final int PVP_PHASE_TICKS = 20 * 60 * 60 * 24 * 7; // 1 week
        static final int COUNTDOWN_DURATION_TICKS = 20 * 10; // 10 seconds
        static final int BOUNDARY_CHECK_INTERVAL = 10;
        static final double BOUNDARY_PUSH_STEP = 1.0;
        static final int BOUNDARY_PUSH_MAX_STEPS = 10;
        static final int DEATH_PENALTY = 10;
        static final int KILL_REWARD = 5;
        static final long BOUNDARY_WARNING_COOLDOWN_MS = 3000L;
        static final long RESET_CONFIRMATION_TIMEOUT_MS = 10000L;
        static final int BUFF_UPDATE_INTERVAL = 60;
        static final int BUFF_DURATION_TICKS = 20 * 60; // 1 minute
        static final int SCOREBOARD_UPDATE_INTERVAL = 20; // 1 second
        static final int DISASTER_INTERVAL_TICKS = 20 * 60 * 5; // 5 minutes
        static final int DISASTER_EFFECT_DURATION_TICKS = 20 * 30; // 600 ticks
        static final int DISASTER_COOLDOWN_TICKS = 20 * 60 * 3; // 3600 ticks
        static final int DISASTER_PULSE_INTERVAL_TICKS = 20 * 25; // 500 ticks
        static final float METEOR_DAMAGE = 8.0F;
        static final int BLIZZARD_FREEZE_TICKS = 20 * 20; // 400 ticks
        static final float FIRE_SHOWER_DAMAGE = 4.0F;
        static final int ICE_SPIKES_SLOW_AMPLIFIER = 1;
        static final int MIRAGE_DURATION_CAP_TICKS = 20 * 30; // 600 ticks
        static final int SANDSTORM_BLIND_AMPLIFIER = 0;
        static final int SPORE_POISON_AMPLIFIER = 0;
        static final int CRYSTAL_STORM_LEVITATION_AMPLIFIER = 0;
    }

    private static Config config;

    private ConfigManager() {
    }

    // Initialization methods
    /**
     * Loads the configuration from the JSON file.
     * If the file is missing or corrupted, it creates a default configuration
     * file and uses the default values.
     */
    @Initialize(priority = 2500)
    public static void loadConfig() {
        File configFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve(CONFIG_FILENAME)
                .toFile();

        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        } else {
            loadExistingConfig(configFile);
        }

        logConfigValues();
    }

    // Private helper methods
    private static void createDefaultConfig(File configFile) {
        config = new Config();
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
            LOGGER.info("Created default config file: {}", CONFIG_FILENAME);
        } catch (IOException e) {
            LOGGER.error("Failed to write default config to {}", CONFIG_FILENAME, e);
        }
    }

    private static void loadExistingConfig(File configFile) {
        try (FileReader reader = new FileReader(configFile)) {
            config = GSON.fromJson(reader, Config.class);

            if (config == null) {
                handleInvalidConfig(configFile);
            } else {
                ensureNestedObjectsNotNull();
                validateAndCorrectConfig();
            }
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to load {} (using defaults): {}",
                    CONFIG_FILENAME, e.getMessage());
            config = new Config();
        }
    }

    private static void handleInvalidConfig(File configFile) {
        LOGGER.warn("Config file {} was empty or invalid. Creating default config.",
                CONFIG_FILENAME);
        config = new Config();

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
            LOGGER.info("Overwrote invalid config file {} with defaults.", CONFIG_FILENAME);
        } catch (IOException e) {
            LOGGER.error("Failed to overwrite invalid config file {} with defaults.",
                    CONFIG_FILENAME, e);
        }
    }

    private static void ensureNestedObjectsNotNull() {
        boolean configRepaired = false;

        if (config.game == null) {
            config.game = new Config.Game();
            configRepaired = true;
        }
        if (config.player == null) {
            config.player = new Config.Player();
            configRepaired = true;
        }
        if (config.buff == null) {
            config.buff = new Config.Buff();
            configRepaired = true;
        }
        if (config.scoreboard == null) {
            config.scoreboard = new Config.Scoreboard();
            configRepaired = true;
        }
        if (config.disaster == null) {
            config.disaster = new Config.Disaster();
            configRepaired = true;
        }

        if (configRepaired) {
            LOGGER.warn("Some configuration sections were missing and have been restored to defaults");
        }
    }

    private static void logConfigValues() {
        LOGGER.info("Configuration loaded successfully:");
        LOGGER.info("  Game: Build phase {}t, PvP phase {}t, Countdown {}t",
                config.game.buildPhaseTicks, config.game.pvpPhaseTicks, config.game.countdownDurationTicks);
        LOGGER.info("  Player: Boundary check {}t, Death penalty {}, Warning cooldown {}ms",
                config.player.boundaryCheckInterval, config.player.deathPenalty,
                config.player.boundaryWarningCooldownMs);
        LOGGER.info("    Kill reward {}",
                config.player.killReward);
        LOGGER.info("    Boundary push step {} blocks, max attempts {}",
                config.player.boundaryPushStep, config.player.boundaryPushMaxSteps);
        LOGGER.info("  Buffs: Update interval {}t, Duration {}t",
                config.buff.buffUpdateInterval, config.buff.buffDurationTicks);
        LOGGER.info("  Scoreboard: Update interval {}t",
                config.scoreboard.updateInterval);
        LOGGER.info("  Disasters: Interval {}t, Meteor damage {}, Blizzard freeze {}t",
                config.disaster.disasterIntervalTicks, config.disaster.meteorDamage,
                config.disaster.blizzardFreezeTicks);
        LOGGER.info("    Fire shower damage {}, Ice spike amp {}, Mirage cap {}t",
                config.disaster.fireShowerDamage, config.disaster.iceSpikesSlowAmplifier,
                config.disaster.mirageDurationCapTicks);
        LOGGER.info("    Sandstorm blind amp {}, Spore poison amp {}, Crystal levitation amp {}",
                config.disaster.sandstormBlindAmplifier, config.disaster.sporePoisonAmplifier,
                config.disaster.crystalStormLevitationAmplifier);
        LOGGER.info("    Disaster pulse interval {}t",
                config.disaster.disasterPulseIntervalTicks);
    }

    /**
     * Validates configuration values and corrects any that are out of bounds.
     * Logs warnings for any corrections made.
     */
    private static void validateAndCorrectConfig() {
        boolean corrected = false;

        corrected |= validateGameSettings();
        corrected |= validatePlayerSettings();
        corrected |= validateBuffSettings();
        corrected |= validateScoreboardSettings();
        corrected |= validateDisasterSettings();

        if (corrected) {
            saveConfig();
        }
    }

    private static boolean validateGameSettings() {
        boolean corrected = false;
        if (config.game.buildPhaseTicks <= 0 || config.game.buildPhaseTicks > Integer.MAX_VALUE / 2) {
            config.game.buildPhaseTicks = Defaults.BUILD_PHASE_TICKS;
            LOGGER.warn("Invalid buildPhaseTicks, reset to default: {}", config.game.buildPhaseTicks);
            corrected = true;
        }

        if (config.game.pvpPhaseTicks <= 0 || config.game.pvpPhaseTicks > Integer.MAX_VALUE / 2) {
            config.game.pvpPhaseTicks = Defaults.PVP_PHASE_TICKS;
            LOGGER.warn("Invalid pvpPhaseTicks, reset to default: {}", config.game.pvpPhaseTicks);
            corrected = true;
        }

        if (config.game.countdownDurationTicks <= 0 || config.game.countdownDurationTicks > 20 * 60) { // Max 1 minute
            config.game.countdownDurationTicks = Defaults.COUNTDOWN_DURATION_TICKS;
            LOGGER.warn("Invalid countdownDurationTicks, reset to default: {}", config.game.countdownDurationTicks);
            corrected = true;
        }

        return corrected;
    }

    private static boolean validatePlayerSettings() {
        boolean corrected = false;
        if (config.player.boundaryCheckInterval <= 0 || config.player.boundaryCheckInterval > 20 * 60) { // Max 1 minute
            config.player.boundaryCheckInterval = Defaults.BOUNDARY_CHECK_INTERVAL;
            LOGGER.warn("Invalid boundaryCheckInterval, reset to default: {}", config.player.boundaryCheckInterval);
            corrected = true;
        }

        if (config.player.deathPenalty < 0 || config.player.deathPenalty > 1000) { // Reasonable max
            config.player.deathPenalty = Defaults.DEATH_PENALTY;
            LOGGER.warn("Invalid deathPenalty, reset to default: {}", config.player.deathPenalty);
            corrected = true;
        }

        if (config.player.killReward < 0 || config.player.killReward > 1000) { // Reasonable max
            config.player.killReward = Defaults.KILL_REWARD;
            LOGGER.warn("Invalid killReward, reset to default: {}", config.player.killReward);
            corrected = true;
        }

        if (config.player.boundaryPushStep <= 0.0 || config.player.boundaryPushStep > 100.0) {
            config.player.boundaryPushStep = Defaults.BOUNDARY_PUSH_STEP;
            LOGGER.warn("Invalid boundaryPushStep, reset to default: {}", config.player.boundaryPushStep);
            corrected = true;
        }

        if (config.player.boundaryPushMaxSteps <= 0 || config.player.boundaryPushMaxSteps > 100) {
            config.player.boundaryPushMaxSteps = Defaults.BOUNDARY_PUSH_MAX_STEPS;
            LOGGER.warn("Invalid boundaryPushMaxSteps, reset to default: {}", config.player.boundaryPushMaxSteps);
            corrected = true;
        }

        return corrected;
    }

    private static boolean validateBuffSettings() {
        boolean corrected = false;
        if (config.buff.buffUpdateInterval <= 0 || config.buff.buffUpdateInterval > 20 * 60) { // Max 1 minute
            config.buff.buffUpdateInterval = Defaults.BUFF_UPDATE_INTERVAL;
            LOGGER.warn("Invalid buffUpdateInterval, reset to default: {}", config.buff.buffUpdateInterval);
            corrected = true;
        }

        if (config.buff.buffDurationTicks <= 0 || config.buff.buffDurationTicks > 20 * 60 * 5) { // Max 5 minutes
            config.buff.buffDurationTicks = Defaults.BUFF_DURATION_TICKS;
            LOGGER.warn("Invalid buffDurationTicks, reset to default: {}", config.buff.buffDurationTicks);
            corrected = true;
        }

        return corrected;
    }

    private static boolean validateScoreboardSettings() {
        boolean corrected = false;
        if (config.scoreboard.updateInterval <= 0 || config.scoreboard.updateInterval > 20 * 60) { // Max 1 minute
            config.scoreboard.updateInterval = Defaults.SCOREBOARD_UPDATE_INTERVAL;
            LOGGER.warn("Invalid scoreboard updateInterval, reset to default: {}", config.scoreboard.updateInterval);
            corrected = true;
        }
        return corrected;
    }

    private static boolean validateDisasterSettings() {
        boolean corrected = false;
        if (config.disaster.disasterIntervalTicks <= 0 || config.disaster.disasterIntervalTicks > 20 * 60 * 60) { // Max
                                                                                                                  // 1
                                                                                                                  // hour
            config.disaster.disasterIntervalTicks = Defaults.DISASTER_INTERVAL_TICKS;
            LOGGER.warn("Invalid disasterIntervalTicks, reset to default: {}", config.disaster.disasterIntervalTicks);
            corrected = true;
        }

        if (config.disaster.meteorDamage <= 0 || config.disaster.meteorDamage > 100.0F) { // Max 50 hearts
            config.disaster.meteorDamage = Defaults.METEOR_DAMAGE;
            LOGGER.warn("Invalid meteorDamage, reset to default: {}", config.disaster.meteorDamage);
            corrected = true;
        }

        if (config.disaster.disasterPulseIntervalTicks <= 0
                || config.disaster.disasterPulseIntervalTicks > 20 * 60 * 60) { // Max 1 hour
            config.disaster.disasterPulseIntervalTicks = Defaults.DISASTER_PULSE_INTERVAL_TICKS;
            LOGGER.warn("Invalid disasterPulseIntervalTicks, reset to default: {}",
                    config.disaster.disasterPulseIntervalTicks);
            corrected = true;
        }

        if (config.disaster.fireShowerDamage <= 0 || config.disaster.fireShowerDamage > 100.0F) {
            config.disaster.fireShowerDamage = Defaults.FIRE_SHOWER_DAMAGE;
            LOGGER.warn("Invalid fireShowerDamage, reset to default: {}", config.disaster.fireShowerDamage);
            corrected = true;
        }

        if (config.disaster.iceSpikesSlowAmplifier < 0 || config.disaster.iceSpikesSlowAmplifier > 5) {
            config.disaster.iceSpikesSlowAmplifier = Defaults.ICE_SPIKES_SLOW_AMPLIFIER;
            LOGGER.warn("Invalid iceSpikesSlowAmplifier, reset to default: {}", config.disaster.iceSpikesSlowAmplifier);
            corrected = true;
        }

        if (config.disaster.mirageDurationCapTicks <= 0 || config.disaster.mirageDurationCapTicks > 20 * 60 * 5) { // Max
                                                                                                                   // 5
                                                                                                                   // minutes
            config.disaster.mirageDurationCapTicks = Defaults.MIRAGE_DURATION_CAP_TICKS;
            LOGGER.warn("Invalid mirageDurationCapTicks, reset to default: {}", config.disaster.mirageDurationCapTicks);
            corrected = true;
        }

        if (config.disaster.sandstormBlindAmplifier < 0 || config.disaster.sandstormBlindAmplifier > 5) {
            config.disaster.sandstormBlindAmplifier = Defaults.SANDSTORM_BLIND_AMPLIFIER;
            LOGGER.warn("Invalid sandstormBlindAmplifier, reset to default: {}",
                    config.disaster.sandstormBlindAmplifier);
            corrected = true;
        }

        if (config.disaster.sporePoisonAmplifier < 0 || config.disaster.sporePoisonAmplifier > 5) {
            config.disaster.sporePoisonAmplifier = Defaults.SPORE_POISON_AMPLIFIER;
            LOGGER.warn("Invalid sporePoisonAmplifier, reset to default: {}", config.disaster.sporePoisonAmplifier);
            corrected = true;
        }

        if (config.disaster.crystalStormLevitationAmplifier < 0
                || config.disaster.crystalStormLevitationAmplifier > 5) {
            config.disaster.crystalStormLevitationAmplifier = Defaults.CRYSTAL_STORM_LEVITATION_AMPLIFIER;
            LOGGER.warn("Invalid crystalStormLevitationAmplifier, reset to default: {}",
                    config.disaster.crystalStormLevitationAmplifier);
            corrected = true;
        }

        return corrected;
    }

    /**
     * Saves the current configuration to the JSON file.
     */
    private static void saveConfig() {
        File configFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve(CONFIG_FILENAME)
                .toFile();

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
            LOGGER.debug("Configuration saved to {}", CONFIG_FILENAME);
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration to {}", CONFIG_FILENAME, e);
        }
    }

    // Game settings getters
    public static int getGameBuildPhaseTicks() {
        return config.game.buildPhaseTicks;
    }

    public static int getGamePvpPhaseTicks() {
        return config.game.pvpPhaseTicks;
    }

    public static int getGameCountdownDurationTicks() {
        return config.game.countdownDurationTicks;
    }

    // Player settings getters
    public static int getPlayerBoundaryCheckInterval() {
        return config.player.boundaryCheckInterval;
    }

    public static int getPlayerDeathPenalty() {
        return config.player.deathPenalty;
    }

    public static long getPlayerBoundaryWarningCooldownMs() {
        return config.player.boundaryWarningCooldownMs;
    }

    public static int getPlayerKillReward() {
        return config.player.killReward;
    }

    public static long getPlayerResetConfirmationTimeoutMs() {
        return config.player.resetConfirmationTimeoutMs;
    }

    // New player push-back getters
    public static double getPlayerBoundaryPushStep() {
        return config.player.boundaryPushStep;
    }

    public static int getPlayerBoundaryPushMaxSteps() {
        return config.player.boundaryPushMaxSteps;
    }

    // Buff settings getters
    public static int getBuffUpdateInterval() {
        return config.buff.buffUpdateInterval;
    }

    public static int getBuffDurationTicks() {
        return config.buff.buffDurationTicks;
    }

    // Scoreboard settings getters
    public static int getScoreboardUpdateInterval() {
        return config.scoreboard.updateInterval;
    }

    // Disaster settings getters
    public static int getDisasterIntervalTicks() {
        return config.disaster.disasterIntervalTicks;
    }

    public static int getDisasterEffectDurationTicks() {
        return config.disaster.disasterEffectDurationTicks;
    }

    public static int getDisasterCooldownTicks() {
        return config.disaster.disasterCooldownTicks;
    }

    public static float getDisasterMeteorDamage() {
        return config.disaster.meteorDamage;
    }

    public static int getDisasterBlizzardFreezeTicks() {
        return config.disaster.blizzardFreezeTicks;
    }

    public static float getDisasterFireShowerDamage() {
        return config.disaster.fireShowerDamage;
    }

    public static int getDisasterIceSpikesSlowAmplifier() {
        return config.disaster.iceSpikesSlowAmplifier;
    }

    public static int getDisasterMirageDurationCapTicks() {
        return config.disaster.mirageDurationCapTicks;
    }

    public static int getDisasterSandstormBlindAmplifier() {
        return config.disaster.sandstormBlindAmplifier;
    }

    public static int getDisasterSporePoisonAmplifier() {
        return config.disaster.sporePoisonAmplifier;
    }

    public static int getDisasterCrystalStormLevitationAmplifier() {
        return config.disaster.crystalStormLevitationAmplifier;
    }

    public static int getDisasterPulseIntervalTicks() {
        return config.disaster.disasterPulseIntervalTicks;
    }

    // Inner classes
    /**
     * Root class representing the structure of the configuration file.
     * Contains nested classes for different setting categories.
     */
    private static class Config {
        Game game = new Game();
        Player player = new Player();
        Buff buff = new Buff();
        Scoreboard scoreboard = new Scoreboard();
        Disaster disaster = new Disaster();

        /**
         * Game-related settings like phase durations and countdowns.
         */
        static class Game {
            /** Duration of the build phase in ticks. Default: 12096000 ticks (1 week). */
            int buildPhaseTicks = Defaults.BUILD_PHASE_TICKS;
            /** Duration of the PvP phase in ticks. Default: 12096000 ticks (1 week). */
            int pvpPhaseTicks = Defaults.PVP_PHASE_TICKS;
            /**
             * Duration in ticks for the pre-game countdown.
             * Default: 200 ticks (10 seconds).
             */
            int countdownDurationTicks = Defaults.COUNTDOWN_DURATION_TICKS;
        }

        /**
         * Player-related settings like boundary checks and penalties.
         */
        static class Player {
            /**
             * Interval in ticks for checking if players are outside their island
             * boundaries. Default: 10 ticks.
             */
            int boundaryCheckInterval = Defaults.BOUNDARY_CHECK_INTERVAL;
            /** Points deducted from a team when a member dies. Default: 10 points. */
            int deathPenalty = Defaults.DEATH_PENALTY;
            /**
             * Cooldown in milliseconds before a player receives another boundary
             * warning message. Default: 3000ms (3 seconds).
             */
            long boundaryWarningCooldownMs = Defaults.BOUNDARY_WARNING_COOLDOWN_MS;
            /**
             * Time window in milliseconds for confirming destructive actions like
             * data reset. Default: 10000ms (10 seconds).
             */
            long resetConfirmationTimeoutMs = Defaults.RESET_CONFIRMATION_TIMEOUT_MS;
            /** How far to push a player back per corrective step (blocks). */
            double boundaryPushStep = Defaults.BOUNDARY_PUSH_STEP;
            /** Maximum number of corrective push attempts. */
            int boundaryPushMaxSteps = Defaults.BOUNDARY_PUSH_MAX_STEPS;
            /**
             * Points awarded to a team when a member kills a player from a different
             * island. Default: 5 points.
             */
            int killReward = Defaults.KILL_REWARD;
        }

        /**
         * Settings related to island buffs.
         */
        static class Buff {
            /**
             * Interval in ticks for applying island-specific buffs.
             * Default: 60 ticks (3 seconds).
             */
            int buffUpdateInterval = Defaults.BUFF_UPDATE_INTERVAL;
            /** Duration in ticks for island buffs. Default: 1200 ticks (1 minute). */
            int buffDurationTicks = Defaults.BUFF_DURATION_TICKS;
        }

        /**
         * Settings related to scoreboard display and updates.
         */
        static class Scoreboard {
            /**
             * Interval in ticks for updating scoreboard scores and team assignments.
             * Default: 20 ticks (1 second).
             */
            int updateInterval = Defaults.SCOREBOARD_UPDATE_INTERVAL;
        }

        /**
         * Settings related to disasters.
         */
        static class Disaster {
            /**
             * Interval in ticks for attempting to trigger a random disaster.
             * Default: 6000 ticks (5 minutes).
             */
            int disasterIntervalTicks = Defaults.DISASTER_INTERVAL_TICKS;
            /**
             * Duration in ticks for disaster status effects (Blindness, Poison,
             * Levitation). Default: 100 ticks (5 seconds).
             */
            int disasterEffectDurationTicks = Defaults.DISASTER_EFFECT_DURATION_TICKS;
            /**
             * Cooldown in ticks before a disaster can re-trigger on the same island.
             * Default: 100 ticks (5 seconds).
             */
            int disasterCooldownTicks = Defaults.DISASTER_COOLDOWN_TICKS;
            /**
             * Damage dealt by a single meteor hit during the METEOR disaster.
             * Default: 8.0F (4 hearts).
             */
            float meteorDamage = Defaults.METEOR_DAMAGE;
            /**
             * Additional freeze ticks applied by the BLIZZARD disaster.
             * Default: 200 ticks (10 seconds).
             */
            int blizzardFreezeTicks = Defaults.BLIZZARD_FREEZE_TICKS;
            /** Damage for FIRE_SHOWER disaster. Default: 4.0F. */
            float fireShowerDamage = Defaults.FIRE_SHOWER_DAMAGE;
            /**
             * Amplifier for ICE_SPIKES slowness (0 = Slowness I). Default: 1 (Slowness II).
             */
            int iceSpikesSlowAmplifier = Defaults.ICE_SPIKES_SLOW_AMPLIFIER;
            /** Maximum duration cap (ticks) for MIRAGE. Default: 200 ticks. */
            int mirageDurationCapTicks = Defaults.MIRAGE_DURATION_CAP_TICKS;
            /** Blindness amplifier for SANDSTORM (0 = Blindness I). */
            int sandstormBlindAmplifier = Defaults.SANDSTORM_BLIND_AMPLIFIER;
            /** Poison amplifier for SPORE (0 = Poison I). */
            int sporePoisonAmplifier = Defaults.SPORE_POISON_AMPLIFIER;
            /** Levitation amplifier for CRYSTAL_STORM (0 = Levitation I). */
            int crystalStormLevitationAmplifier = Defaults.CRYSTAL_STORM_LEVITATION_AMPLIFIER;
            /**
             * How often (ticks) to reapply disaster effects while the disaster is active.
             */
            int disasterPulseIntervalTicks = Defaults.DISASTER_PULSE_INTERVAL_TICKS;
        }
    }
}
