package de.nofelix.stormboundisles.game;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Broadcasts a nicely formatted clock string to all players at the start of every hour.
 * <p>
 * Runs server-side and uses the existing initialization registry so it doesn't
 * require client updates. The message is broadcast once when the hour starts
 * and once when the server starts.
 */
public final class HourlyClockAnnouncer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static volatile int lastBroadcastHour = -1;
    private static volatile long lastMinuteCheck = 0L;

    private HourlyClockAnnouncer() { throw new UnsupportedOperationException("Utility class"); }

    @Initialize(priority = 1600, description = "Initialize hourly clock announcer")
    public static void initialize() {
        StormboundIslesMod.LOGGER.info("Initializing HourlyClockAnnouncer");

        // Broadcast once when the server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            broadcastCurrentTime(server, true);
            lastBroadcastHour = LocalTime.now().getHour();
        });

        // Check periodically on server ticks; throttle checks to once per 30 seconds
        ServerTickEvents.END_SERVER_TICK.register(HourlyClockAnnouncer::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastMinuteCheck < 60000L) return; // check every 60s
        lastMinuteCheck = now;

        LocalTime t = LocalTime.now();
        int hour = t.getHour();
        int minute = t.getMinute();

        // Only broadcast at the start of an hour and only once
        if (minute == 0 && hour != lastBroadcastHour) {
            broadcastCurrentTime(server, false);
            lastBroadcastHour = hour;
        }
    }

    private static void broadcastCurrentTime(MinecraftServer server, boolean onStartup) {
        LocalTime now = LocalTime.now();
        String timeStr = now.format(TIME_FORMATTER);
        String message = onStartup
                ? String.format("Server time is now §e%s§r", timeStr)
                : String.format("§6[Clock] §rIt is now §e%s§r on the server", timeStr);

        try {
            server.getPlayerManager().broadcast(Text.literal(message), false);
            StormboundIslesMod.LOGGER.info("HourlyClockAnnouncer broadcasted time: {}", timeStr);
        } catch (Exception e) {
            StormboundIslesMod.LOGGER.error("Failed to broadcast hourly time", e);
        }
    }
}
