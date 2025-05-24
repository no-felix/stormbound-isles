package de.nofelix.stormboundisles.game;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

/**
 * Utility class for sending messages to a player's action bar.
 * 
 * Provides a simple interface for displaying temporary messages in the player's
 * action bar area, which appears above the hotbar and is commonly used for
 * status updates and notifications.
 * 
 * Example usage:
 * ```java
 * ActionbarNotifier.send(player, "§cDisaster: Meteor shower!");
 * ActionbarNotifier.send(player, "§aTeam Red captured the island!");
 * ```
 */
public final class ActionbarNotifier {

    private ActionbarNotifier() {
    }

    /**
     * Sends a message to be displayed on the specified player's action bar.
     * 
     * The action bar is the area above the hotbar that displays temporary
     * status messages. Messages sent here will replace any existing action
     * bar content for that player.
     *
     * @param player  The player to send the message to
     * @param message The text message to display (supports Minecraft formatting codes)
     */
    public static void send(@NotNull ServerPlayerEntity player, @NotNull String message) {
        player.sendMessage(Text.literal(message), true);
    }
}