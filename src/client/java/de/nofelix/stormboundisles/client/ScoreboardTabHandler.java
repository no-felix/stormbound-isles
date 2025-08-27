package de.nofelix.stormboundisles.client;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Handles client-side TAB key functionality for scoreboard display.
 * <p>
 * This handler manages:
 * <ul>
 * <li>TAB key press detection</li>
 * <li>Dynamic scoreboard sidebar showing/hiding</li>
 * <li>Client-side state management</li>
 * </ul>
 * 
 * Works directly with the client-side scoreboard instance to avoid
 * server-client communication overhead.
 */
@Environment(EnvType.CLIENT)
public class ScoreboardTabHandler {
    private static boolean tabPressed = false;
    private static boolean scoreboardVisible = false;

    /**
     * Private constructor to prevent instantiation.
     */
    private ScoreboardTabHandler() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Initializes the TAB key handler for scoreboard display.
     * 
     * @see Initialize
     */
    @Initialize(priority = 800, description = "Initialize client-side TAB scoreboard handler")
    public static void initialize() {
        registerClientEvents();
        StormboundIslesMod.LOGGER.info("ScoreboardTabHandler initialized successfully.");
    }

    /**
     * Registers client-side event listeners for TAB key handling.
     */
    private static void registerClientEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                return;
            }

            boolean currentTabState = isTabPressed(client);

            if (currentTabState != tabPressed) {
                tabPressed = currentTabState;

                if (tabPressed) {
                    showScoreboard();
                } else {
                    hideScoreboard();
                }
            }
        });

        StormboundIslesMod.LOGGER.info("ScoreboardTabHandler client events registered.");
    }

    /**
     * Checks if the TAB key is currently pressed.
     * 
     * @param client The Minecraft client instance
     * @return true if TAB is pressed
     */
    private static boolean isTabPressed(MinecraftClient client) {
        return InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_TAB);
    }

    /**
     * Shows the scoreboard when TAB is pressed.
     * (Now handled by mixin - this just updates state)
     */
    private static void showScoreboard() {
        if (!scoreboardVisible) {
            scoreboardVisible = true;
            StormboundIslesMod.LOGGER.debug("TAB pressed - scoreboard should be visible.");
        }
    }

    /**
     * Hides the scoreboard when TAB is released.
     * (Now handled by mixin - this just updates state)
     */
    private static void hideScoreboard() {
        if (scoreboardVisible) {
            scoreboardVisible = false;
            StormboundIslesMod.LOGGER.debug("TAB released - scoreboard should be hidden.");
        }
    }

    /**
     * Gets the current TAB press state.
     * 
     * @return true if TAB is currently pressed
     */
    public static boolean isTabCurrentlyPressed() {
        return tabPressed;
    }

    /**
     * Gets the current scoreboard visibility state.
     * 
     * @return true if scoreboard is currently visible
     */
    public static boolean isScoreboardCurrentlyVisible() {
        return scoreboardVisible;
    }
}
