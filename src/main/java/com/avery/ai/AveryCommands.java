package com.avery.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /avery spawn | despawn | key <ключ> | model <модель> | url <адрес> | status
 * Доступно хозяину мира (в одиночной игре) и операторам.
 */
public final class AveryCommands {
	private AveryCommands() {}

	private static boolean allowed(ServerCommandSource s) {
		if (s.hasPermissionLevel(2)) return true;
		return s.getEntity() instanceof ServerPlayerEntity p && s.getServer().isHost(p.getGameProfile());
	}

	/** /avery act <действие> [предмет/блок] [число]: проверка тела Эвери без нейросети. */
	private static int act(CommandContext<ServerCommandSource> ctx, String arg, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource s = ctx.getSource();
		if (AveryMod.bot == null) {
			s.sendFeedback(() -> Text.literal("Эвери сейчас нет в игре"), false);
			return 0;
		}
		JsonObject o = new JsonObject();
		o.addProperty("type", StringArgumentType.getString(ctx, "type"));
		if (!arg.isEmpty()) {
			o.addProperty("item", arg);
			o.addProperty("block", arg);
		}
		o.addProperty("count", count);
		AveryBrain.perform(s.getServer(), AveryMod.bot, s.getPlayerOrThrow(), o);
		return 1;
	}

	public static void register(CommandDispatcher<ServerCommandSource> d, CommandRegistryAccess access,
			CommandManager.RegistrationEnvironment env) {
		d.register(literal("avery")
				.requires(AveryCommands::allowed)
				.then(literal("spawn").executes(ctx -> {
					ServerCommandSource s = ctx.getSource();
					ServerPlayerEntity p = s.getPlayerOrThrow();
					if (AveryMod.bot != null) {
						s.sendFeedback(() -> Text.literal("Эвери уже в игре"), false);
						return 0;
					}
					AveryMod.bot = AveryPlayer.spawnNear(p);
					return 1;
				}))
				.then(literal("despawn").executes(ctx -> {
					if (AveryMod.bot == null) {
						ctx.getSource().sendFeedback(() -> Text.literal("Эвери сейчас нет в игре"), false);
						return 0;
					}
					AveryBrain.despawn(ctx.getSource().getServer());
					return 1;
				}))
				.then(literal("key").then(argument("key", StringArgumentType.greedyString()).executes(ctx -> {
					AveryConfig.get().apiKey = StringArgumentType.getString(ctx, "key").trim();
					AveryConfig.save();
					ctx.getSource().sendFeedback(() -> Text.literal("Ключ API сохранён ✅"), false);
					return 1;
				})))
				.then(literal("model").then(argument("model", StringArgumentType.greedyString()).executes(ctx -> {
					AveryConfig.get().model = StringArgumentType.getString(ctx, "model").trim();
					AveryConfig.save();
					ctx.getSource().sendFeedback(() -> Text.literal("Модель: " + AveryConfig.get().model), false);
					return 1;
				})))
				.then(literal("url").then(argument("url", StringArgumentType.greedyString()).executes(ctx -> {
					AveryConfig.get().apiUrl = StringArgumentType.getString(ctx, "url").trim();
					AveryConfig.save();
					ctx.getSource().sendFeedback(() -> Text.literal("Адрес API сохранён ✅"), false);
					return 1;
				})))
				.then(literal("act").then(argument("type", StringArgumentType.word())
						.executes(ctx -> act(ctx, "", 1))
						.then(argument("arg", StringArgumentType.word())
								.executes(ctx -> act(ctx, StringArgumentType.getString(ctx, "arg"), 1))
								.then(argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> act(ctx, StringArgumentType.getString(ctx, "arg"),
												IntegerArgumentType.getInteger(ctx, "count")))))))
				.then(literal("status").executes(ctx -> {
					AveryConfig c = AveryConfig.get();
					String msg = "Эвери: " + (AveryMod.bot != null ? "в игре" : "нет в игре")
							+ "\nКлюч: " + (c.apiKey == null || c.apiKey.isBlank() ? "не задан ❌" : "задан ✅")
							+ "\nМодель: " + c.model + "\nАдрес: " + c.apiUrl;
					ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
					return 1;
				})));
	}
}
