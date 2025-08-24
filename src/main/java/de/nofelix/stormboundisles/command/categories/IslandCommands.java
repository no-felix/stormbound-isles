package de.nofelix.stormboundisles.command.categories;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.nofelix.stormboundisles.command.CommandCategory;
import de.nofelix.stormboundisles.command.util.CommandPermissions;
import de.nofelix.stormboundisles.command.util.CommandSuggestions;
import de.nofelix.stormboundisles.command.util.PolygonBuilderManager;
import de.nofelix.stormboundisles.command.util.PolygonBuilderManager.PolygonBuilder;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.data.Island;
import de.nofelix.stormboundisles.data.Zone;
import de.nofelix.stormboundisles.disaster.DisasterManager;
import de.nofelix.stormboundisles.disaster.DisasterType;
import de.nofelix.stormboundisles.util.Constants;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Handles island management commands for the Stormbound Isles mod.
 * <p>
 * This class provides moderator-level commands for managing islands, including:
 * <ul>
 * <li>Disaster management (triggering and canceling disasters)</li>
 * <li>Zone definition (polygon and rectangle zones)</li>
 * <li>Island listing and information</li>
 * <li>Spawn point configuration</li>
 * </ul>
 * <p>
 * The commands are organized in a hierarchical structure with nested
 * subcommands
 * to provide a clear and intuitive interface for server moderators.
 */
public class IslandCommands implements CommandCategory {
        /**
         * Registers all island management commands with the root command.
         * 
         * @param rootCommand The root command to add these commands to
         */
        @Override
        public void register(LiteralArgumentBuilder<ServerCommandSource> rootCommand) {
                // Island category
                LiteralArgumentBuilder<ServerCommandSource> islandCommand = CommandManager.literal("island")
                                .requires(CommandPermissions.requiresPermissionLevel(
                                                CommandPermissions.MODERATOR_PERMISSION_LEVEL));

                // Register disaster subcategory
                registerDisasterCommands(islandCommand);

                // Island list command
                islandCommand.then(CommandManager.literal("list")
                                .executes(ctx -> {
                                        StringBuilder sb = new StringBuilder("§6Islands:§r\n");
                                        for (Island isl : DataManager.getIslands().values()) {
                                                String zoneInfo;
                                                Zone z = isl.getZone();
                                                if (z == null) {
                                                        zoneInfo = "§cNot set§r";
                                                } else {
                                                        zoneInfo = "§aPolygon (" + z.getPoints().size() + " points)§r";
                                                }
                                                sb.append("§b").append(isl.getId())
                                                                .append("§r (§e").append(isl.getType()).append("§r)")
                                                                .append(" | Team: §d").append(isl.getTeamName())
                                                                .append("§r | Zone: ").append(zoneInfo)
                                                                .append("\n");
                                        }
                                        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                        return 1;
                                }));

                // Register zone commands
                registerZoneCommands(islandCommand);

                // Island setspawn command
                islandCommand.then(CommandManager.literal("setspawn")
                                .then(CommandManager.argument("islandId", StringArgumentType.word())
                                                .suggests(CommandSuggestions.ISLAND_ID_SUGGESTIONS)
                                                .executes(ctx -> {
                                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                                        if (player == null) {
                                                                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                                                                return 0;
                                                        }

                                                        Island isl = DataManager.getIsland(
                                                                        StringArgumentType.getString(ctx, "islandId"));
                                                        if (isl == null) {
                                                                ctx.getSource().sendError(Text
                                                                                .literal("Island does not exist.")
                                                                                .formatted(Formatting.RED));
                                                                return 0;
                                                        }
                                                        BlockPos pos = player.getBlockPos();
                                                        isl.setSpawnPoint(pos.getX(), pos.getY(), pos.getZ());
                                                        DataManager.saveAll();
                                                        ctx.getSource().sendFeedback(() -> Text
                                                                        .literal("Spawn for island " + isl.getId()
                                                                                        + " set to " + pos)
                                                                        .formatted(Formatting.GREEN), false);
                                                        return 1;
                                                })));

                // Add island category to root command
                rootCommand.then(islandCommand);
        }

        /**
         * Registers disaster management commands.
         * <p>
         * These commands allow moderators to trigger specific disasters on islands
         * or cancel active disasters. The commands interact with the DisasterManager
         * to manage island disaster states.
         * 
         * @param islandCommand The parent island command to add these subcommands to
         */
        private void registerDisasterCommands(LiteralArgumentBuilder<ServerCommandSource> islandCommand) {
                // Disaster subcategory
                LiteralArgumentBuilder<ServerCommandSource> disasterCommand = CommandManager.literal("disaster");

                // Disaster trigger command
                disasterCommand.then(CommandManager.literal("trigger")
                                .then(CommandManager.argument("islandId", StringArgumentType.word())
                                                .suggests(CommandSuggestions.ISLAND_ID_SUGGESTIONS)
                                                .then(CommandManager.argument("type", StringArgumentType.word())
                                                                .suggests(CommandSuggestions.DISASTER_TYPE_SUGGESTIONS)
                                                                .executes(ctx -> {
                                                                        String id = StringArgumentType.getString(ctx,
                                                                                        "islandId");
                                                                        String typeStr = StringArgumentType
                                                                                        .getString(ctx, "type")
                                                                                        .toUpperCase();
                                                                        try {
                                                                                DisasterType type = DisasterType
                                                                                                .valueOf(typeStr);
                                                                                DisasterManager.triggerDisaster(ctx
                                                                                                .getSource()
                                                                                                .getServer(), id, type);
                                                                                ctx.getSource().sendFeedback(() -> Text
                                                                                                .literal("Disaster triggered: "
                                                                                                                + type
                                                                                                                + " on "
                                                                                                                + id)
                                                                                                .formatted(Formatting.YELLOW),
                                                                                                false);
                                                                                return 1;
                                                                        } catch (IllegalArgumentException e) {
                                                                                ctx.getSource().sendError(
                                                                                                Constants.INVALID_ARGUMENTS);
                                                                                return 0;
                                                                        }
                                                                }))));

                // Disaster cancel command
                disasterCommand.then(CommandManager.literal("cancel")
                                .then(CommandManager.argument("islandId", StringArgumentType.word())
                                                .suggests(CommandSuggestions.ISLAND_ID_SUGGESTIONS)
                                                .executes(ctx -> {
                                                        String id = StringArgumentType.getString(ctx, "islandId");
                                                        if (DisasterManager.cancelActiveDisaster(
                                                                        ctx.getSource().getServer(), id)) {
                                                                ctx.getSource().sendFeedback(() -> Text
                                                                                .literal("Cancelled active disaster on "
                                                                                                + id)
                                                                                .formatted(Formatting.GREEN), false);
                                                                return 1;
                                                        } else {
                                                                ctx.getSource().sendError(Text
                                                                                .literal("No active disaster on " + id)
                                                                                .formatted(Formatting.RED));
                                                                return 0;
                                                        }
                                                })));

                // Add disaster subcategory to island category
                islandCommand.then(disasterCommand);
        }

        /**
         * Registers zone management commands.
         * <p>
         * These commands allow moderators to define island zones using either
         * rectangles (from two corner points) or arbitrary polygons (from multiple
         * points).
         * Zones are used to determine island boundaries for various game mechanics.
         * 
         * @param islandCommand The parent island command to add these subcommands to
         */
        private void registerZoneCommands(LiteralArgumentBuilder<ServerCommandSource> islandCommand) {
                // Zone subcategory
                LiteralArgumentBuilder<ServerCommandSource> zoneCommand = CommandManager.literal("zone");

                // Register polygon zone commands
                registerPolygonCommands(zoneCommand);

                // Rectangle zone command
                zoneCommand.then(CommandManager.literal("rectangle")
                                .then(CommandManager.argument("islandId", StringArgumentType.word())
                                                .suggests(CommandSuggestions.ISLAND_ID_SUGGESTIONS)
                                                .executes(ctx -> {
                                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                                        if (player == null) {
                                                                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                                                                return 0;
                                                        }

                                                        UUID uid = player.getUuid();
                                                        String islandId = StringArgumentType.getString(ctx, "islandId");

                                                        PolygonBuilder pb = PolygonBuilderManager.getBuilder(uid);
                                                        if (pb == null || !islandId.equals(pb.getIslandId())) {
                                                                // New rectangle definition
                                                                pb = PolygonBuilderManager.startPolygon(uid, islandId);
                                                                pb.addPoint(player.getBlockPos()); // Add first corner

                                                                ctx.getSource().sendFeedback(() -> Text.literal(
                                                                                "First corner set at your position.")
                                                                                .formatted(Formatting.GREEN), false);
                                                                ctx.getSource().sendFeedback(() -> Text.literal(
                                                                                "Move to the opposite corner and run this command again to create a rectangle.")
                                                                                .formatted(Formatting.GRAY), false);
                                                        } else if (pb.getPointCount() == 1) {
                                                                // Second corner - create the rectangle
                                                                Island isl = DataManager.getIsland(pb.getIslandId());
                                                                if (isl == null) {
                                                                        ctx.getSource().sendError(Text.literal(
                                                                                        "Island does not exist.")
                                                                                        .formatted(Formatting.RED));
                                                                        return 0;
                                                                }

                                                                // Create a rectangle from the two points
                                                                BlockPos firstPos = pb.getPoints().get(0);
                                                                BlockPos secondPos = player.getBlockPos();
                                                                String finalIslandId = pb.getIslandId();

                                                                isl.setZone(pb.createRectangle(secondPos));
                                                                PolygonBuilderManager.removeBuilder(uid);
                                                                DataManager.saveAll();
                                                                ctx.getSource().sendFeedback(() -> Text
                                                                                .literal("Rectangular zone for island "
                                                                                                + finalIslandId
                                                                                                + " set from " +
                                                                                                firstPos + " to "
                                                                                                + secondPos)
                                                                                .formatted(Formatting.GREEN), false);
                                                        }
                                                        return 1;
                                                })));

                // Add zone subcategory to island category
                islandCommand.then(zoneCommand);
        }

        /**
         * Registers polygon zone commands.
         * <p>
         * These commands provide a multi-step process for creating complex polygon
         * zones:
         * <ol>
         * <li>Start a polygon definition for an island</li>
         * <li>Add multiple points to the polygon at the player's position</li>
         * <li>Finish and save the polygon when all points are added</li>
         * </ol>
         * <p>
         * This allows for more precise zone definitions than simple rectangles.
         * 
         * @param zoneCommand The parent zone command to add these subcommands to
         */
        private void registerPolygonCommands(LiteralArgumentBuilder<ServerCommandSource> zoneCommand) {
                // Polygon zone subcategory
                LiteralArgumentBuilder<ServerCommandSource> polygonCommand = CommandManager.literal("polygon");

                // Polygon start command
                polygonCommand.then(CommandManager.literal("start")
                                .then(CommandManager.argument("islandId", StringArgumentType.word())
                                                .suggests(CommandSuggestions.ISLAND_ID_SUGGESTIONS)
                                                .executes(ctx -> {
                                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                                        if (player == null) {
                                                                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                                                                return 0;
                                                        }

                                                        UUID uid = player.getUuid();
                                                        String islandId = StringArgumentType.getString(ctx, "islandId");
                                                        PolygonBuilder pb = PolygonBuilderManager.startPolygon(uid,
                                                                        islandId);

                                                        ctx.getSource().sendFeedback(() -> Text.literal(
                                                                        "Polygon definition started for island "
                                                                                        + pb.getIslandId())
                                                                        .formatted(Formatting.GREEN), false);
                                                        ctx.getSource().sendFeedback(() -> Text.literal(
                                                                        "Use /sbi island zone polygon add to add points at your position.")
                                                                        .formatted(Formatting.GRAY), false);
                                                        return 1;
                                                })));

                // Polygon add command
                polygonCommand.then(CommandManager.literal("add")
                                .executes(ctx -> {
                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                        if (player == null) {
                                                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                                                return 0;
                                        }

                                        UUID uid = player.getUuid();
                                        PolygonBuilder pb = PolygonBuilderManager.getBuilder(uid);
                                        if (pb == null) {
                                                ctx.getSource().sendError(Text.literal(
                                                                "Use /sbi island zone polygon start <islandId> first.")
                                                                .formatted(Formatting.RED));
                                                return 0;
                                        }
                                        pb.addPoint(player.getBlockPos());
                                        ctx.getSource().sendFeedback(() -> Text
                                                        .literal("Added point " + player.getBlockPos()
                                                                        + " to polygon (Point " + pb.getPointCount()
                                                                        + ")")
                                                        .formatted(Formatting.GREEN), false);
                                        return 1;
                                }));

                // Polygon finish command
                polygonCommand.then(CommandManager.literal("finish")
                                .executes(ctx -> {
                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                        if (player == null) {
                                                ctx.getSource().sendError(Constants.PLAYER_ONLY);
                                                return 0;
                                        }

                                        UUID uid = player.getUuid();
                                        PolygonBuilder pb = PolygonBuilderManager.removeBuilder(uid);
                                        if (pb == null) {
                                                ctx.getSource().sendError(Text.literal(
                                                                "No polygon in progress. Use /sbi island zone polygon start <islandId> first.")
                                                                .formatted(Formatting.RED));
                                                return 0;
                                        }
                                        if (pb.getPointCount() < 3) {
                                                ctx.getSource().sendError(Constants.INVALID_ARGUMENTS);
                                                return 0;
                                        }
                                        Island isl = DataManager.getIsland(pb.getIslandId());
                                        if (isl == null) {
                                                ctx.getSource().sendError(Text.literal("Island does not exist.")
                                                                .formatted(Formatting.RED));
                                                return 0;
                                        }
                                        isl.setZone(pb.createPolygon());
                                        DataManager.saveAll();
                                        ctx.getSource().sendFeedback(() -> Text
                                                        .literal("Polygon zone with " + pb.getPointCount()
                                                                        + " points set for island " + pb.getIslandId())
                                                        .formatted(Formatting.GREEN), false);
                                        return 1;
                                }));

                // Add polygon subcategory to zone category
                zoneCommand.then(polygonCommand);
        }
}