package de.nofelix.stormboundisles.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.nofelix.stormboundisles.command.categories.*;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.IslandType;
import de.nofelix.stormboundisles.data.Team;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Main command manager for the Stormbound Isles mod.
 * <p>
 * This class serves as the central entry point for the command system,
 * coordinating all command categories
 * and handling their registration with the Minecraft command dispatcher. It
 * delegates command execution
 * to specialized category implementations that each handle a specific subset of
 * functionality.
 */
public class CommandManager {
    /** The root command name for all mod commands. */
    private static final String SBI = "sbi";

    // Command categories
    private static final AdminCommands adminCommands = new AdminCommands();
    private static final IslandCommands islandCommands = new IslandCommands();
    private static final TeamCommands teamCommands = new TeamCommands();
    private static final PointsCommands pointsCommands = new PointsCommands();
    private static final PlayerCommands playerCommands = new PlayerCommands();

    /**
     * Private constructor to prevent instantiation.
     */
    private CommandManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Registers all commands with the Minecraft command system.
     * This hooks into Fabric's command registration API to add the mod's commands
     * to the game.
     */
    @Initialize(priority = 1400, description = "Register mod commands")
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Main command builder
            LiteralArgumentBuilder<ServerCommandSource> sbiCommand = net.minecraft.server.command.CommandManager
                    .literal(SBI);

            // Register all command categories
            adminCommands.register(sbiCommand);
            islandCommands.register(sbiCommand);
            teamCommands.register(sbiCommand);
            pointsCommands.register(sbiCommand);
            playerCommands.register(sbiCommand);

            // Register the command
            dispatcher.register(sbiCommand);
        });
    }

    /**
     * Initializes all islands and teams, ensuring they exist and are properly
     * linked.
     * <p>
     * For each {@link IslandType}, this method:
     * <ol>
     * <li>Creates an island with the lowercase island type name as ID if it doesn't
     * exist</li>
     * <li>Creates a team with the island type name if it doesn't exist</li>
     * <li>Links the island and team together if they aren't already linked</li>
     * </ol>
     * <p>
     * This method is automatically called during mod initialization through
     * the annotation-based initialization system.
     */
    @Initialize(priority = 1200, description = "Initialize default islands and teams")
    public static void initIslandsAndTeams() {
        for (IslandType type : IslandType.values()) {
            String id = type.name().toLowerCase();
            Island island = DataManager.getIsland(id);
            if (island == null) {
                island = new Island(id, type);
                DataManager.putIsland(island);
            }

            String teamName = type.name();
            Team team = DataManager.getTeam(teamName);
            if (team == null) {
                team = new Team(teamName);
                DataManager.putTeam(team);
            }

            if (island.getTeamName() == null)
                island.setTeamName(teamName);
            if (team.getIslandId() == null)
                team.setIslandId(id);
        }
        DataManager.saveAll();
    }
}