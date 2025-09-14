package de.nofelix.stormboundisles.command.categories;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.nofelix.stormboundisles.command.CommandCategory;
import de.nofelix.stormboundisles.command.util.CommandPermissions;
import de.nofelix.stormboundisles.command.util.CommandSuggestions;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.util.Constants;
import de.nofelix.stormboundisles.util.ZoneChecker;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Handles player-focused commands in the Stormbound Isles mod.
 * <p>
 * This class provides commands for accessing player information. Regular
 * players
 * can view their own information, while moderators can view information about
 * any player.
 * The information includes the player's team, position, and current island.
 */
public class PlayerCommands implements CommandCategory {
    /**
     * Registers all player-focused commands with the root command.
     * 
     * @param rootCommand The root command to add these commands to
     */
    @Override
    public void register(LiteralArgumentBuilder<ServerCommandSource> rootCommand) {
        // Player category - available to everyone
        LiteralArgumentBuilder<ServerCommandSource> playerCommand = CommandManager.literal("player");

        // Player info command base node - no permission check here
        LiteralArgumentBuilder<ServerCommandSource> infoCommand = CommandManager.literal("info");

        // Self-info execution (available to all)
        infoCommand.executes(ctx -> {
            // Info about the player running the command
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) {
                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                return 0;
            }

            return displayPlayerInfo(ctx, player);
        });

        // Other player info (moderator only)
        infoCommand.then(CommandManager.argument("player", StringArgumentType.word())
                .requires(CommandPermissions.requiresPermissionLevel(CommandPermissions.MODERATOR_PERMISSION_LEVEL))
                .suggests(CommandSuggestions.PLAYER_SUGGESTIONS)
                .executes(ctx -> {
                    ServerPlayerEntity target = ctx.getSource().getServer()
                            .getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "player"));
                    if (target == null) {
                        ctx.getSource().sendError(Constants.PLAYER_NOT_FOUND);
                        return 0;
                    }

                    return displayPlayerInfo(ctx, target);
                }));

        // Add info command to player category
        playerCommand.then(infoCommand);

        // Add player category to root command
        rootCommand.then(playerCommand);
    }

    /**
     * Displays detailed information about a player.
     * <p>
     * This method gathers and formats information about the specified player,
     * including:
     * <ul>
     * <li>Player name</li>
     * <li>Team membership</li>
     * <li>Current position (coordinates)</li>
     * <li>Current island (based on polygon zone containment)</li>
     * </ul>
     * 
     * @param ctx    The command context
     * @param player The player to display information about
     * @return 1 for success
     */
    private int displayPlayerInfo(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
            ServerPlayerEntity player) {
        StringBuilder sb = new StringBuilder(
                Constants.PLAYER_PREFIX + player.getName().getString() + Constants.RESET + "\n");

        // Find player's team
        String teamInfo = Constants.NO_TEAM;
        for (Team team : DataManager.getTeams().values()) {
            if (team.getMembers().contains(player.getUuid())) {
                teamInfo = team.getName();
                break;
            }
        }

        sb.append(Constants.TEAM_PREFIX).append(teamInfo).append(Constants.RESET).append("\n");
        sb.append(Constants.POSITION_PREFIX).append(player.getBlockPos().getX())
                .append(", ").append(player.getBlockPos().getY())
                .append(", ").append(player.getBlockPos().getZ())
                .append(Constants.RESET).append("\n");

        // Check what island they're in
        String currentIsland = ZoneChecker.findPlayerIsland(player, DataManager.getIslands());
        if (currentIsland == null) {
            currentIsland = Constants.NO_ISLAND;
        }
        sb.append(Constants.ISLAND_PREFIX).append(currentIsland).append(Constants.RESET);

        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }
}