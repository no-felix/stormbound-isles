package de.nofelix.stormboundisles.command.categories;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.nofelix.stormboundisles.command.CommandCategory;
import de.nofelix.stormboundisles.command.util.CommandPermissions;
import de.nofelix.stormboundisles.command.util.CommandSuggestions;
import de.nofelix.stormboundisles.config.ConfigManager;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.game.GameManager;
import de.nofelix.stormboundisles.game.DailyRewardManager;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.util.Constants;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Handles administrative commands for the Stormbound Isles mod.
 * <p>
 * This class manages high-level administrative commands that require admin
 * permission level (3).
 * These commands include game lifecycle management (start, stop, phase control)
 * and data reset
 * functionality. The reset command includes a confirmation mechanism to prevent
 * accidental data loss.
 */
public class AdminCommands implements CommandCategory {
    /** Maps player UUIDs to timestamps for reset command confirmation */
    private final Map<UUID, Long> resetConfirmations = new Object2ObjectOpenHashMap<>();

    /**
     * Registers all administrative commands with the root command.
     * 
     * @param rootCommand The root command to add these commands to
     */
    @Override
    public void register(LiteralArgumentBuilder<ServerCommandSource> rootCommand) {
        // Admin category
        LiteralArgumentBuilder<ServerCommandSource> adminCommand = CommandManager.literal("admin")
                .requires(CommandPermissions.requiresPermissionLevel(CommandPermissions.ADMIN_PERMISSION_LEVEL));

        // Game subcategory
        LiteralArgumentBuilder<ServerCommandSource> gameCommand = CommandManager.literal("game");

        // Game start command
        gameCommand.then(CommandManager.literal("start")
                .executes(ctx -> {
                    GameManager.startCountdown(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal("Countdown to game start initiated.")
                            .formatted(Formatting.GREEN), true); // Broadcast to all players
                    return 1;
                }));

        // Game stop command
        gameCommand.then(CommandManager.literal("stop")
                .executes(ctx -> {
                    GameManager.stopGame(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal("Game stopped.")
                            .formatted(Formatting.RED), true); // Broadcast to all players
                    return 1;
                }));

        // Game phase command
        gameCommand.then(CommandManager.literal("phase")
                .then(CommandManager.argument("phase", StringArgumentType.word())
                        .suggests(CommandSuggestions.GAME_PHASE_SUGGESTIONS)
                        .executes(ctx -> {
                            String phaseStr = StringArgumentType.getString(ctx, "phase").toUpperCase();
                            try {
                                GamePhase phase = GamePhase.valueOf(phaseStr);
                                GameManager.setPhase(phase, ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(
                                        () -> Text.literal("Phase set to " + phase).formatted(Formatting.GREEN), false);
                                return 1;
                            } catch (IllegalArgumentException e) {
                                ctx.getSource().sendError(Constants.INVALID_ARGUMENTS);
                                return 0;
                            }
                        })));

        // Add game subcategory to admin category
        adminCommand.then(gameCommand);

        // Admin config reload command
        adminCommand.then(CommandManager.literal("reload")
                .then(CommandManager.literal("config")
                        .executes(ctx -> {
                            try {
                                ConfigManager.loadConfig();
                                ctx.getSource().sendFeedback(
                                        () -> Text.literal("Configuration reloaded successfully.")
                                                .formatted(Formatting.GREEN),
                                        false);
                                return 1;
                            } catch (Exception e) {
                                ctx.getSource().sendError(
                                        Text.literal("Failed to reload configuration: " + e.getMessage())
                                                .formatted(Formatting.RED));
                                return 0;
                            }
                        })));

        // Admin daily reward debug command
        adminCommand.then(CommandManager.literal("debug")
                .then(CommandManager.literal("daily")
                        .executes(ctx -> {
                            Map<String, Object> debugInfo = DailyRewardManager.getDebugInfo();
                            
                            ctx.getSource().sendFeedback(
                                    () -> Text.literal("=== Daily Reward System Debug ===")
                                            .formatted(Formatting.GOLD),
                                    false);
                                    
                            for (Map.Entry<String, Object> entry : debugInfo.entrySet()) {
                                ctx.getSource().sendFeedback(
                                        () -> Text.literal(String.format("  %s: %s", 
                                                entry.getKey(), entry.getValue()))
                                                .formatted(Formatting.WHITE),
                                        false);
                            }
                            
                            return 1;
                        })));

        // Admin reset command
        adminCommand.then(CommandManager.literal("reset")
                .executes(ctx -> {
                    UUID playerUuid = ctx.getSource().getPlayer().getUuid();
                    long currentTime = System.currentTimeMillis();

                    // Clean up expired confirmations to prevent memory buildup
                    cleanupExpiredConfirmations(currentTime);

                    if (resetConfirmations.containsKey(playerUuid) &&
                            currentTime - resetConfirmations.get(playerUuid) < ConfigManager
                                    .getPlayerResetConfirmationTimeoutMs()) {
                        // Confirmed, perform reset
                        resetConfirmations.remove(playerUuid);
                        DataManager.clearIslands();
                        DataManager.clearTeams();

                        // Re-initialize with default islands (delegated to CommandManager)
                        ctx.getSource().sendFeedback(
                                () -> Text.literal("Game data reset successfully.").formatted(Formatting.GREEN), false);
                        return 1;
                    } else {
                        // Ask for confirmation
                        resetConfirmations.put(playerUuid, currentTime);
                        ctx.getSource().sendFeedback(() -> Text.literal(Constants.RESET_CONFIRMATION_MESSAGE)
                                .formatted(Formatting.RED, Formatting.BOLD), false);
                        return 1;
                    }
                }));

        // Add admin category to root command
        rootCommand.then(adminCommand);
    }

    /**
     * Cleans up expired confirmation entries to prevent memory buildup.
     * <p>
     * This method iterates through all reset confirmations and removes any that
     * have
     * exceeded the timeout window, preventing memory leaks from abandoned
     * confirmation requests.
     * 
     * @param currentTime The current system time in milliseconds
     */
    private void cleanupExpiredConfirmations(long currentTime) {
        Iterator<Map.Entry<UUID, Long>> iterator = resetConfirmations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (currentTime - entry.getValue() >= ConfigManager.getPlayerResetConfirmationTimeoutMs()) {
                iterator.remove();
            }
        }
    }
}