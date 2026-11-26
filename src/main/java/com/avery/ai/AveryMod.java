package com.avery.ai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AveryMod implements ModInitializer {
	public static final String MOD_ID = "avery";
	public static final Logger LOGGER = LoggerFactory.getLogger("Avery AI");

	/** Постоянный UUID Эвери: по нему сохраняется его инвентарь и подменяется скин. */
	public static final UUID BOT_UUID = UUID.nameUUIDFromBytes("OfflinePlayer:AveryAI".getBytes(StandardCharsets.UTF_8));
	public static final Identifier SKIN = new Identifier(MOD_ID, "textures/skin/avery.png");

	public static AveryPlayer bot;

	@Override
	public void onInitialize() {
		AveryConfig.load();
		AveryBrain.init();

		CommandRegistrationCallback.EVENT.register(AveryCommands::register);
		ServerTickEvents.END_SERVER_TICK.register(AveryBrain::tick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				AveryBrain.onChat(sender, message.getSignedContent()));
		UseEntityCallback.EVENT.register(AveryBrain::onUseEntity);

		// Игрок зашёл в мир (в том числе в только что созданный) — Эвери появляется рядом.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity p = handler.player;
			if (p instanceof AveryPlayer) return;
			AveryBrain.scheduleSpawn(p.getUuid());
		});

		// Последний живой игрок вышел — Эвери тоже уходит (на выделенных серверах).
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler.player instanceof AveryPlayer) return;
			server.execute(() -> {
				boolean anyone = server.getPlayerManager().getPlayerList().stream()
						.anyMatch(p -> !(p instanceof AveryPlayer) && p != handler.player);
				if (!anyone) AveryBrain.despawn(server);
			});
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AveryBrain.despawn(server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> bot = null);
	}
}
