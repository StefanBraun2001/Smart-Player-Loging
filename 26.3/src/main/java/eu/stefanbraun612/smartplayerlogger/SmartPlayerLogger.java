package eu.stefanbraun612.smartplayerlogger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class SmartPlayerLogger implements ModInitializer {
	public static final String MOD_ID = "smartplayerlogger";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile LoggerService service;

	@Override
	public void onInitialize() {
		Path serverDir = FabricLoader.getInstance().getGameDir();
		try {
			service = LoggerService.start(serverDir.resolve("P_Logger"), LOGGER::info, LOGGER::warn);
		} catch (IOException e) {
			LOGGER.error("Could not set up the P_Logger folder - player logging is disabled.", e);
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(server ->
				service.seedHistoryIfFirstRun(server.getWorldPath(LevelResource.ROOT), serverDir));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> service.serverStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> service.stop());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			service.playerJoined(player.getUUID(), player.getName().getString());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			service.playerLeft(player.getUUID(), player.getName().getString());
		});
	}

	/** Called from the login mixin; safe before init or after a failed init. */
	public static void reportRefusal(String name, String reason) {
		LoggerService s = service;
		if (s != null) {
			s.connectionRefused(name, reason);
		}
	}
}
