package de.nofelix.stormboundisles.command.util;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.nofelix.stormboundisles.data.DataManager;
import de.nofelix.stormboundisles.disaster.DisasterType;
import de.nofelix.stormboundisles.game.GamePhase;
import de.nofelix.stormboundisles.init.Initialize;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class for command argument suggestions in the Stormbound Isles mod.
 * <p>
 * This class centralizes all suggestion providers used throughout the command
 * system,
 * making them easily accessible from any command implementation. It provides
 * suggestion
 * providers for islands, teams, players, game phases, and disaster types.
 * <p>
 * All providers are automatically initialized during mod startup via the
 * {@link Initialize} annotation.
 */
public final class CommandSuggestions {

        /** Suggests island IDs from the data manager */
        public static SuggestionProvider<ServerCommandSource> ISLAND_ID_SUGGESTIONS;
        /** Suggests team names from the data manager */
        public static SuggestionProvider<ServerCommandSource> TEAM_NAME_SUGGESTIONS;
        /** Suggests disaster types from the DisasterType enum */
        public static SuggestionProvider<ServerCommandSource> DISASTER_TYPE_SUGGESTIONS;
        /** Suggests game phases from the GamePhase enum */
        public static SuggestionProvider<ServerCommandSource> GAME_PHASE_SUGGESTIONS;
        /** Suggests online player names from the server's player manager */
        public static SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTIONS;

        /**
         * Private constructor to prevent instantiation.
         */
        private CommandSuggestions() {
                throw new UnsupportedOperationException("Utility class");
        }

        /**
         * Initializes all suggestion providers.
         * <p>
         * This method is automatically called during mod initialization through
         * the annotation-based initialization system.
         */
        @Initialize(priority = 2000, description = "Initialize command suggestion providers")
        public static void initialize() {
                List<String> DISASTER_TYPE_NAMES;
                List<String> GAME_PHASE_NAMES;
                // Cache enum values to avoid recreating them for each suggestion
                GAME_PHASE_NAMES = Stream.of(GamePhase.values())
                                .map(Enum::name)
                                .toList();

                DISASTER_TYPE_NAMES = Stream.of(DisasterType.values())
                                .map(Enum::name)
                                .toList();

                // Create suggestion providers
                ISLAND_ID_SUGGESTIONS = (ctx, builder) -> CommandSource
                                .suggestMatching(DataManager.getIslands().keySet(), builder);

                TEAM_NAME_SUGGESTIONS = (ctx, builder) -> CommandSource.suggestMatching(DataManager.getTeams().keySet(),
                                builder);

                DISASTER_TYPE_SUGGESTIONS = (ctx, builder) -> CommandSource.suggestMatching(DISASTER_TYPE_NAMES,
                                builder);

                GAME_PHASE_SUGGESTIONS = (ctx, builder) -> CommandSource.suggestMatching(GAME_PHASE_NAMES, builder);

                PLAYER_SUGGESTIONS = (ctx, builder) -> CommandSource.suggestMatching(
                                ctx.getSource().getServer().getPlayerManager().getPlayerList()
                                                .stream().map(p -> p.getName().getString())
                                                .collect(Collectors.toList()),
                                builder);
        }
}