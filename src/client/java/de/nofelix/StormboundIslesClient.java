package de.nofelix;

import de.nofelix.stormboundisles.client.ScoreboardTabHandler;
import net.fabricmc.api.ClientModInitializer;

public class StormboundIslesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Initialize client-specific logic
		ScoreboardTabHandler.initialize();
	}
}