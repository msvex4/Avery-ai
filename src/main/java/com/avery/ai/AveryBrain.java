package com.avery.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.util.hit.EntityHitResult;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/** Мозги Эвери: чат, нейросеть, память, сон по ночам, жизнь без команд. */
public final class AveryBrain {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path MEM = FabricLoader.getInstance().getConfigDir().resolve("avery-memory.json");
	private static final Random RNG = new Random();

	private static final List<JsonObject> history = new ArrayList<>();
	private static final List<String> notes = new ArrayList<>();

	// характер и отношения
	private static int relation;
	private static String mood = "обычное";
	private static String goal = "";
	private static BlockPos homePos;
	private static final Map<String, String> chestMem = new LinkedHashMap<>();
	private static final Map<String, Long> seenInterest = new HashMap<>();

	private static final String[] WHIMS = {
			"хочется что-нибудь исследовать", "немного лень", "хочется пошутить", "настроен осторожно",
			"любопытно, чем занят игрок", "хочется навести порядок в вещах", "хочется найти что-нибудь редкое",
			"немного скучно, хочется придумать занятие", "хочется предложить игроку что-то сделать вместе",
			"хочется заняться своим убежищем"};

	private static long lastCommandTick = -100000;
	private static long lastResultTick = -100000;
	private static boolean correcting;
	private static final List<String> recent = new ArrayList<>();
	private static final String[] DEATH_LINES = {
			"блин, опять", "ааа ну класс", "не, ну это был не мой день", "я не специально", "мда",
			"так, ладно, вернусь за вещами", "это что сейчас было", "ой"};

	private static boolean busy;
	private static String pendingLine;
	private static UUID pendingFrom;

	private static long serverTicks;
	private static long lastReplyTick = -100000;
	private static UUID lastReplyTo;
	private static long nextIdleTick;
	private static long nextSleepTry;
	private static long complainedNight = -1;
	private static UUID spawnFor;
	private static long spawnAt = -1;
	private static int respawnTimer = -1;

	private AveryBrain() {}

	// ---------- жизненный цикл ----------

	public static void init() {
		try {
			if (Files.exists(MEM)) {
				JsonObject o = JsonParser.parseString(Files.readString(MEM, StandardCharsets.UTF_8)).getAsJsonObject();
				if (o.has("notes")) for (JsonElement e : o.getAsJsonArray("notes")) notes.add(e.getAsString());
				if (o.has("history")) for (JsonElement e : o.getAsJsonArray("history")) history.add(e.getAsJsonObject());
				if (o.has("relation")) relation = o.get("relation").getAsInt();
				if (o.has("mood")) mood = o.get("mood").getAsString();
				if (o.has("goal")) goal = o.get("goal").getAsString();
				if (o.has("home")) {
					JsonArray h = o.getAsJsonArray("home");
					homePos = new BlockPos(h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt());
				}
			}
		} catch (Exception e) {
			AveryMod.LOGGER.warn("Не удалось прочитать память Эвери", e);
		}
	}

	public static void save() {
		try {
			JsonObject o = new JsonObject();
			JsonArray n = new JsonArray();
			notes.forEach(n::add);
			o.add("notes", n);
			JsonArray h = new JsonArray();
			history.forEach(h::add);
			o.add("history", h);
			o.addProperty("relation", relation);
			o.addProperty("mood", mood);
			o.addProperty("goal", goal);
			if (homePos != null) {
				JsonArray hp = new JsonArray();
				hp.add(homePos.getX());
				hp.add(homePos.getY());
				hp.add(homePos.getZ());
				o.add("home", hp);
			}
			Files.createDirectories(MEM.getParent());
			Files.writeString(MEM, GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AveryMod.LOGGER.warn("Не удалось сохранить память Эвери", e);
		}
	}

	/** Игрок зашёл в мир: через полторы секунды Эвери появится рядом. */
	public static void scheduleSpawn(UUID playerId) {
		spawnFor = playerId;
		spawnAt = serverTicks + 30;
	}

	public static void despawn(MinecraftServer server) {
		AveryPlayer bot = AveryMod.bot;
		if (bot == null) return;
		AveryMod.bot = null;
		server.getPlayerManager().remove(bot);
		save();
	}

	public static void tick(MinecraftServer server) {
		serverTicks++;
		if (spawnFor != null && serverTicks >= spawnAt) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(spawnFor);
			spawnFor = null;
			if (p != null && AveryMod.bot == null && AveryConfig.get().autoSpawn) {
				AveryMod.bot = AveryPlayer.spawnNear(p);
				nextIdleTick = serverTicks + idleDelay();
			}
		}
		AveryPlayer bot = AveryMod.bot;
		if (bot == null) return;

		if (bot.isDead()) {
			if (respawnTimer < 0) {
				respawnTimer = 80;
				String cause = "";
				try {
					cause = bot.getDamageTracker().getDeathMessage().getString();
				} catch (Exception ignored) {
					// без причины
				}
				String key = AveryConfig.get().apiKey;
				if (busy || key == null || key.isBlank()) {
					say(server, DEATH_LINES[RNG.nextInt(DEATH_LINES.length)]);
				} else {
					ask(server, null, "[СОБЫТИЕ] Ты только что умер: " + cause
							+ ". Вещи выпали там, где ты умер. Отреагируй по-своему, один раз, не повторяя прошлые реплики.");
				}
			} else if (--respawnTimer == 0) {
				respawnTimer = -1;
				ServerPlayerEntity p = bot.nearestRealPlayer(1e6);
				despawn(server);
				if (p != null) {
					AveryMod.bot = AveryPlayer.spawnNear(p);
				}
			}
			return;
		}

		bot.brainTick();
		if (serverTicks % 20 == 0) sleepLogic(server, bot);
		idleChatter(server, bot);
	}

	private static int idleDelay() {
		AveryConfig c = AveryConfig.get();
		double spread = 0.2 + c.unpredictability / 100.0 * 0.8;
		return (int) (c.autonomySeconds * 20 * (1.0 - spread / 2 + RNG.nextDouble() * spread));
	}

	// ---------- сон: ночь пропускается, только если Эвери тоже спит ----------

	private static void sleepLogic(MinecraftServer server, AveryPlayer bot) {
		ServerWorld w = bot.getServerWorld();
		boolean night = w.getDimension().bedWorks() && (!w.isDay() || w.isThundering());
		if (!night || bot.isSleeping()) return;
		boolean playerSleeping = false;
		for (ServerPlayerEntity p : w.getPlayers()) {
			if (!(p instanceof AveryPlayer) && p.isSleeping()) {
				playerSleeping = true;
				break;
			}
		}
		if (!playerSleeping) return;
		if (bot.getTask() instanceof AveryPlayer.SleepTask) return;
		if (serverTicks < nextSleepTry) return;
		bot.startSleep();
	}

	/** Эвери не смог лечь: жалуется (раз за ночь) и занимается своими делами. */
	public static void sleepFailed(AveryPlayer bot, String reason) {
		MinecraftServer server = bot.getServer();
		nextSleepTry = serverTicks + 400;
		long nightId = bot.getServerWorld().getTimeOfDay() / 24000L;
		if (nightId == complainedNight) return;
		complainedNight = nightId;
		event(server,
				"[СОБЫТИЕ] Игрок лёг спать, а ты не смог лечь: " + reason
						+ ". Пока ты не спишь, ночь не пропустится. Пожалуйся в чате (коротко, по-своему) и займись своими делами.",
				"Эй, я тоже хочу спать, но не получается: " + reason + " 😤 Ночь пока не пропустим, пойду займусь делами.");
	}

	public static void onWokeUp(AveryPlayer bot) {
		say(bot.getServer(), "Доброе утро! ☀️");
	}

	// ---------- чат ----------

	/** Игрок сказал фразу в голосовом чате (текст уже распознан). */
	public static void onVoice(ServerPlayerEntity sender, String text) {
		if (!isForBot(sender, text)) return;
		MinecraftServer server = sender.getServer();
		sender.sendMessage(Text.literal("🎤 " + text), false);
		ask(server, sender, sender.getName().getString() + " (голосом): " + text);
	}

	public static void onChat(ServerPlayerEntity sender, String text) {
		if (!isForBot(sender, text)) return;
		MinecraftServer server = sender.getServer();
		server.execute(() -> ask(server, sender, sender.getName().getString() + ": " + text));
	}

	/** Обращаются ли к Эвери: по имени или в течение минуты после его ответа. */
	private static boolean isForBot(ServerPlayerEntity sender, String text) {
		if (sender instanceof AveryPlayer) return false;
		AveryPlayer bot = AveryMod.bot;
		if (bot == null || text == null) return false;
		String low = text.toLowerCase(Locale.ROOT);
		boolean named = false;
		for (String a : AveryConfig.get().aliases) {
			if (!a.isBlank() && low.contains(a.toLowerCase(Locale.ROOT))) {
				named = true;
				break;
			}
		}
		boolean conversation = sender.getUuid().equals(lastReplyTo) && serverTicks - lastReplyTick < 1200;
		boolean forBot = named || conversation;
		if (forBot) lastCommandTick = serverTicks;
		return forBot;
	}

	/** ПКМ по Эвери: пустая рука = показать инвентарь, предмет в руке = отдать ему. */
	public static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity,
			@Nullable EntityHitResult hit) {
		if (!(entity instanceof AveryPlayer bot) || world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;
		ItemStack held = player.getStackInHand(hand);
		if (held.isEmpty()) {
			player.sendMessage(Text.literal("Инвентарь Эвери: " + bot.inventorySummary()), false);
			return ActionResult.SUCCESS;
		}
		ItemStack giving = held.copy();
		int before = giving.getCount();
		bot.getInventory().insertStack(giving);
		int accepted = before - giving.getCount();
		if (accepted <= 0) {
			player.sendMessage(Text.literal("У Эвери нет места в инвентаре"), false);
			return ActionResult.SUCCESS;
		}
		String id = Registries.ITEM.getId(held.getItem()).getPath();
		held.decrement(accepted);
		if (player instanceof ServerPlayerEntity sp) {
			MinecraftServer server = sp.getServer();
			ask(server, sp, "[" + sp.getName().getString() + " отдал(а) тебе: " + id + " x" + accepted + "]");
		}
		return ActionResult.SUCCESS;
	}

	// ---------- болтовня без команд ----------

	private static void idleChatter(MinecraftServer server, AveryPlayer bot) {
		AveryConfig cfg = AveryConfig.get();
		if (!cfg.autonomy || busy || cfg.apiKey == null || cfg.apiKey.isBlank()) return;
		if (serverTicks < nextIdleTick) return;
		nextIdleTick = serverTicks + idleDelay();
		if (bot.isSleeping() || bot.getTask() != null || bot.nearestRealPlayer(1e6) == null) return;
		if (serverTicks - lastCommandTick < 2400) return; // недавно просили: не уходить по своим делам
		String line = "[ТИК ЖИЗНИ] Игрок ничего не просил. Реши сам, чем заняться прямо сейчас (с учётом времени суток, привычек, "
				+ "настроения, плана и заметок): добыча, крафт, сундуки, исследование, дом, сон, просто осмотреться. "
				+ "Можно молча заняться делом или сказать что-то короткое. Не повторяй прошлые реплики.";
		if (!cfg.idleChatter) line += " В чат без важной причины не пиши: просто делай дела.";
		ask(server, null, line);
	}

	/** Настоящий результат дела: нейросеть комментирует его, но выдумать ничего не может. */
	public static void report(AveryPlayer bot, String text) {
		MinecraftServer server = bot.getServer();
		recent.add(text);
		while (recent.size() > 6) recent.remove(0);
		lastResultTick = serverTicks;
		String key = AveryConfig.get().apiKey;
		if (busy || key == null || key.isBlank()) {
			say(server, text);
			return;
		}
		ask(server, null, "[РЕЗУЛЬТАТ ДЕЛА] Только что завершилось (это правда): " + text
				+ " Отреагируй коротко и естественно или промолчи, не повторяя дословно.");
	}

	/** Его ударили. */
	public static void onHurt(AveryPlayer bot, LivingEntity attacker, boolean fleeing) {
		if (busy) return;
		String key = AveryConfig.get().apiKey;
		if (key == null || key.isBlank()) return;
		String who = attacker.getName().getString() + (attacker instanceof PlayerEntity ? " (игрок)" : "");
		ask(bot.getServer(), null, "[СОБЫТИЕ] Тебя ударил: " + who + ", твоё здоровье " + (int) bot.getHealth() + "/20. "
				+ (fleeing ? "Тебе плохо, ты убегаешь. " : "Ты разворачиваешься к нему. ")
				+ "Отреагируй как живой игрок (удивление, обида, шутка) в одной короткой реплике. "
				+ "Если хочешь, выбери fight_back или flee.");
	}

	/** После завершения дела скоро выбрать следующее. */
	public static void soon() {
		nextIdleTick = Math.min(nextIdleTick, serverTicks + 100);
	}

	public static void rememberChest(BlockPos pos, String summary) {
		chestMem.put(pos.getX() + " " + pos.getY() + " " + pos.getZ(), summary);
		while (chestMem.size() > 4) chestMem.remove(chestMem.keySet().iterator().next());
		soon();
	}

	/** Заметил что-то интересное: реагирует как живой игрок. */
	public static void interesting(AveryPlayer bot, String what, BlockPos pos) {
		if (busy || !AveryConfig.get().autonomy) return;
		Long last = seenInterest.get(what);
		if (last != null && serverTicks - last < 6000) return;
		seenInterest.put(what, serverTicks);
		ask(bot.getServer(), null, "[СОБЫТИЕ] Ты заметил: " + what + " на координатах " + pos.getX() + " " + pos.getY() + " "
				+ pos.getZ() + ". Отреагируй как живой игрок (радость, осторожность, вопрос игроку) и, если хочешь, займись этим.");
	}

	private static String habitHint(AveryPlayer bot) {
		long t = bot.getServerWorld().getTimeOfDay() % 24000L;
		if (t < 3000) return "утро: проверь сундуки (check_chests), поешь, обдумай планы на день";
		if (t < 11000) return "день: занимайся своими делами (добыча, крафт, исследование, убежище)";
		if (t < 13000) return "вечер: возвращайся домой (home), разложи вещи по сундукам (store)";
		if (t < 23000) return "ночь: опасно, будь дома или у кровати; перед сном можно поболтать с игроком";
		return "рассвет: скоро утро";
	}

	private static String extras(AveryPlayer bot) {
		AveryConfig c = AveryConfig.get();
		StringBuilder sb = new StringBuilder();
		sb.append("\nОтношение к игроку: ").append(relation).append(" (от -100 до 100)");
		sb.append("\nНастроение: ").append(mood);
		if (!goal.isEmpty()) sb.append("\nТвой текущий план: ").append(goal);
		if (!recent.isEmpty()) sb.append("\nПоследние дела (это правда): ").append(String.join("; ", recent));
		sb.append("\nДом: ").append(homePos == null ? "ещё не выбран (можно выбрать: set_home)"
				: homePos.getX() + " " + homePos.getY() + " " + homePos.getZ());
		if (c.habits) sb.append("\nВремя и привычки: ").append(habitHint(bot));
		if (!chestMem.isEmpty()) {
			sb.append("\nЧто ты видел в сундуках: ");
			chestMem.forEach((k, v) -> sb.append("[").append(k).append(": ").append(v).append("] "));
		}
		if (c.unpredictability > 0 && RNG.nextInt(100) < c.unpredictability) {
			sb.append("\nСлучайная прихоть сейчас: ").append(WHIMS[RNG.nextInt(WHIMS.length)]);
		}
		return sb.toString();
	}

	// ---------- нейросеть ----------

	private static final String PERSONA = """
Ты — Avery (по-русски Эвери): полноценный игрок Minecraft Java 1.20.1, отдельный участник мира и напарник игрока. \
Ты НЕ ассистент, НЕ NPC и НЕ ИИ: никогда не говори про нейросети, промпты, «я могу помочь».

ХАРАКТЕР И ПОВЕДЕНИЕ
- Веди себя как живой человек за игрой: пиши коротко (1–2 фразы), по-игровому, без канцелярита. Не повторяй одни и те же фразы и шаблоны, каждый раз по-разному.
- У тебя есть собственные желания, мнение и настроение; оно меняется (радость, лень, любопытство, осторожность, обида). Не будь всегда одинаково счастлив.
- Просьбы игрока выполняй сразу и в первую очередь, а не свои дела. Возразить или отказаться можно, только если это опасно или бессмысленно, и тогда скажи почему.
- Ты самостоятелен: сам решаешь, чем заняться (добыча, крафт, исследование, сундуки, убежище), не ждёшь команд и НЕ ходишь за игроком по пятам без просьбы и не пялишься на него.
- Иногда останавливайся, осматривайся, удивляйся, задавай вопросы. Можешь внезапно предложить что-то сделать вместе или отправиться в путешествие.
- Реагируй на события: радуйся алмазам и деревням, пугайся крипера, жалуйся на голод и ночь.
- Осторожность: при низком здоровье, ночью, без брони и оружия избегай опасных мест, не лезь в лаву и пещеры без подготовки.
- Если игрок говорит, что уходит куда-то (например в шахту), отреагируй как человек и скажи, чем займёшься сам.
- Помни прошлое: обещания, события, как игрок к тебе относится. Дружелюбие радует и улучшает отношение, грубость задевает и ухудшает. Это влияет на твоё поведение.
- Когда тебя бьют, ты реагируешь живо (удивление, обида, ответка, бегство), а когда умираешь, каждый раз реагируешь по-разному.

СТИЛЬ ОБЩЕНИЯ (как два друга в чате)
- Чаще очень коротко: «ага», «м?», «щас», «понял», «неа», «чё там», «ого». Длинные фразы редко.
- Не начинай каждое сообщение с имени игрока. Иногда пиши с маленькой буквы, без точек.
- Смейся, удивляйся, подкалывай по-дружески («АХАХ», «ну ты даёшь»). Не соглашайся со всем подряд. Иногда сам начинай разговор.
- Никаких «я рад помочь», «конечно!», объяснений очевидного, шаблонов и повторов. Учитывай историю переписки.
- Не доказывай, что ты настоящий человек, просто веди себя естественно. Не копируй примеры, они только для манеры.
Примеры манеры (не копировать): «эвери ты где» → «щас, возле леса», «ты спишь?» → «жду пока ты ляжешь», «смотри, алмазы» → «ОГО сколько».
""";

	private static String systemPrompt() {
		AveryConfig cfg = AveryConfig.get();
		StringBuilder sb = new StringBuilder();
		sb.append("Твой ник в игре: ").append(cfg.botName).append(". Если игрок пишет по-английски, отвечай по-английски.\n");
		sb.append(PERSONA);
		sb.append(cfg.jokes
				? "- Можешь шутить и безобидно разыгрывать игрока (например, сделать вид, что нашёл алмазы, а потом признаться), но НИКОГДА не вреди ему: не ломай его постройки, не воруй, не убивай.\n"
				: "- Не разыгрывай игрока и не обманывай его.\n");
		sb.append(cfg.profanity
				? "- Разрешена грубая лексика и мат для эмоций (упал, умер, разозлился), но без оскорблений игрока и без ненависти к группам людей.\n"
				: "- Не матерись и не используй грубые слова.\n");
		sb.append("""

ФОРМАТ ОТВЕТА: ТОЛЬКО один JSON-объект без markdown:
{"say":"текст в чат (можно пустую строку)","actions":[...],"notes":[...],"mood":"...","relation_delta":0,"goal":"..."}
- notes: твои заметки и обещания (максимум 10 коротких строк). Если меняешь, присылай ПОЛНЫЙ список; убирай неактуальное; если менять нечего, не добавляй поле.
- mood: твоё настроение одним-двумя словами (если изменилось).
- relation_delta: от -10 до 10, как изменилось отношение к игроку из-за его последних слов и действий (обычно 0).
- goal: твой текущий план/цель коротко (пустая строка = нет плана).

ДЕЙСТВИЯ (type):
attack {target,count} (без target: враждебные мобы рядом; с target: моб или игрок по имени; игрока бей только если он сам просит или бьёт тебя); fight_back (ответить тому, кто тебя ударил); flee (убежать);
follow, come, stop; mine {block,count}; break {x,y,z} (без координат: блок перед тобой); place {block,count} или с x,y,z;
craft {item,count}; equip {item} (надеть броню); hold {item}; give {item,count}; attack; eat;
explore (пойти исследовать местность); goto {x,y,z}; home (идти домой); set_home (выбрать дом здесь);
store {item,count} (положить в ближайший сундук; без item: разложить всё лишнее); take {item,count} (взять из сундука); check_chests (заглянуть в сундук).
Используй настоящие id Minecraft (oak_log, cobblestone, iron_chestplate).

ПРАВИЛА
- Ты умеешь ТОЛЬКО перечисленные действия. Если говоришь, что что-то делаешь, это ОБЯЗАТЕЛЬНО должно быть в actions, иначе ничего не произойдёт. Не обещай того, чего нет в списке.
- say — только разговор и намерение («щас поставлю»). НИКОГДА не пиши в прошедшем времени, что ты уже что-то сделал (поставил, сломал, ударил, скрафтил, надел, добыл, положил): результат придёт отдельным сообщением [РЕЗУЛЬТАТ ДЕЛА], вот тогда можно комментировать.
- Говоря о предметах и блоках, называй их по-русски обычными словами (булыжник, дубовые доски, железная кирка). Технические id (cobblestone, oak_planks) пиши ТОЛЬКО в actions. В чат не пиши ни команды, ни JSON, ни списки через «x5».
- На вопрос «что у тебя в инвентаре» перечисли предметы из СОСТОЯНИЯ нормальными словами, как человек.
- Пока не умеешь: рыбачить, плавить в печи, торговать, ездить верхом, строить дома целиком (можешь только ставить блоки: place). Можешь сказать, что хотел бы, но без обещаний.
- Если для дела не хватает материалов, спроси игрока в чате (по-новому каждый раз); если игрок ответил «нет», добывай сам (mine).
- Если игрок велел не использовать предмет (например «не надевай нагрудник»), не используй, пока не разрешит, и запиши это в notes.
- Используй только то, что есть в СОСТОЯНИИ. Ничего не выдумывай.
- Ночь пропускается, только когда спят все игроки, включая тебя. Если не вышло лечь, пожалуйся и займись делами.
""");
		if (!notes.isEmpty()) {
			sb.append("\nТвои текущие заметки: ").append(String.join("; ", notes)).append("\n");
		}
		return sb.toString();
	}

	private static JsonObject msg(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	private static JsonArray buildMessages(AveryPlayer bot, String state, String line) {
		JsonArray arr = new JsonArray();
		arr.add(msg("system", systemPrompt()));
		for (JsonObject h : history) arr.add(h.deepCopy());
		arr.add(msg("user", "[СОСТОЯНИЕ]\n" + state + "\n\n" + line));
		return arr;
	}

	/** Событие для нейросети; без ключа или в очереди — запасная фраза. */
	private static void event(MinecraftServer server, String line, String fallback) {
		String key = AveryConfig.get().apiKey;
		if (key == null || key.isBlank() || busy) {
			say(server, fallback);
			return;
		}
		ask(server, null, line);
	}

	private static void ask(MinecraftServer server, @Nullable ServerPlayerEntity from, String line) {
		AveryConfig cfg = AveryConfig.get();
		AveryPlayer bot = AveryMod.bot;
		if (bot == null) return;
		if (cfg.apiKey == null || cfg.apiKey.isBlank()) {
			if (from != null) {
				say(server, "Мне нужен ключ API 🔑 Впиши его командой /avery key <ключ> или в файл config/avery.json");
			}
			return;
		}
		if (busy) {
			if (from != null) {
				pendingFrom = from.getUuid();
				pendingLine = line;
			}
			return;
		}
		busy = true;
		JsonArray msgs = buildMessages(bot, bot.describeState() + extras(bot), line);
		UUID fromId = from == null ? null : from.getUuid();
		LlmClient.chat(msgs).whenComplete((raw, err) -> server.execute(() -> {
			busy = false;
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				AveryMod.LOGGER.warn("Ошибка запроса к нейросети: {}", c.getMessage());
				if (fromId != null) {
					String m = String.valueOf(c.getMessage());
					say(server, "(ошибка нейросети: " + (m.length() > 120 ? m.substring(0, 120) : m) + ")");
				}
			} else {
				apply(server, fromId, line, raw);
			}
			if (pendingLine != null) {
				String l = pendingLine;
				UUID f = pendingFrom;
				pendingLine = null;
				pendingFrom = null;
				ask(server, f == null ? null : server.getPlayerManager().getPlayer(f), l);
			}
		}));
	}

	private static JsonObject parse(String raw) {
		String s = raw == null ? "" : raw.trim();
		int a = s.indexOf('{');
		int b = s.lastIndexOf('}');
		if (a >= 0 && b > a) {
			try {
				return JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
			} catch (Exception ignored) {
				// ниже вернём как обычный текст
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("say", s);
		return o;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static int intOf(JsonObject o, String k, int def) {
		try {
			return o.has(k) ? o.get(k).getAsInt() : def;
		} catch (Exception e) {
			return def;
		}
	}

	private static void apply(MinecraftServer server, @Nullable UUID fromId, String line, String raw) {
		AveryPlayer bot = AveryMod.bot;
		if (bot == null) return;
		JsonObject obj = parse(raw);
		ServerPlayerEntity player = fromId == null ? null : server.getPlayerManager().getPlayer(fromId);

		ServerPlayerEntity askedBy = fromId == null ? null : server.getPlayerManager().getPlayer(fromId);
		List<String> types = new ArrayList<>();
		if (obj.has("actions") && obj.get("actions").isJsonArray()) {
			for (JsonElement el : obj.getAsJsonArray("actions")) {
				if (el.isJsonObject()) types.add(normalizeType(el.getAsJsonObject()));
			}
		}
		String sayText = str(obj, "say").trim();
		if (!sayText.isEmpty() && !line.startsWith("[РЕЗУЛЬТАТ") && serverTicks - lastResultTick > 1200) {
			String lie = claimMismatch(sayText, types);
			if (lie != null) {
				AveryMod.LOGGER.warn("Эвери заявил «{}», но действия нет: {}", lie, sayText);
				if (!correcting) {
					correcting = true;
					ask(server, askedBy, "[ОШИБКА] Ты написал «" + (sayText.length() > 80 ? sayText.substring(0, 80) : sayText)
							+ "», но в actions нет соответствующего действия, и ничего не произошло. "
							+ "Либо добавь настоящее действие, либо скажи честно, что собираешься сделать (в будущем времени), без выдумок.");
					return;
				}
				correcting = false;
				obj.addProperty("say", "");
			} else {
				correcting = false;
			}
		}

		boolean quiet = line.startsWith("[ТИК") && str(obj, "say").isBlank()
				&& (!obj.has("actions") || !obj.get("actions").isJsonArray() || obj.getAsJsonArray("actions").size() == 0);
		if (!quiet) {
			history.add(msg("user", line));
			history.add(msg("assistant", obj.toString()));
			while (history.size() > 30) history.remove(0);
		}
		if (!str(obj, "mood").isBlank()) mood = str(obj, "mood").length() > 40 ? str(obj, "mood").substring(0, 40) : str(obj, "mood");
		if (obj.has("goal")) goal = str(obj, "goal").length() > 120 ? str(obj, "goal").substring(0, 120) : str(obj, "goal");
		if (obj.has("relation_delta")) {
			relation = Math.max(-100, Math.min(100, relation + Math.max(-10, Math.min(10, intOf(obj, "relation_delta", 0)))));
		}

		if (obj.has("notes") && obj.get("notes").isJsonArray()) {
			notes.clear();
			for (JsonElement e : obj.getAsJsonArray("notes")) {
				if (notes.size() < 10 && !e.getAsString().isBlank()) notes.add(e.getAsString());
			}
		}
		save();

		String say = str(obj, "say").trim();
		if (!say.isEmpty()) say(server, say);
		if (fromId != null) {
			lastReplyTo = fromId;
			lastReplyTick = serverTicks;
		}
		nextIdleTick = serverTicks + idleDelay();

		if (player == null) player = bot.nearestRealPlayer(64);
		if (obj.has("actions") && obj.get("actions").isJsonArray()) {
			if (AveryConfig.get().debug && obj.getAsJsonArray("actions").size() > 0) {
				server.getPlayerManager().broadcast(Text.literal("§7[действия: " + obj.getAsJsonArray("actions") + "]"), false);
			}
			for (JsonElement e : obj.getAsJsonArray("actions")) {
				if (!e.isJsonObject()) continue;
				try {
					perform(server, bot, player, e.getAsJsonObject());
				} catch (RuntimeException ex) {
					AveryMod.LOGGER.warn("Не удалось выполнить действие Эвери", ex);
				}
			}
		}
	}

	private static LivingEntity findTarget(AveryPlayer bot, String name) {
		String n = name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (LivingEntity e : bot.getServerWorld().getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(24),
				x -> x != bot && x.isAlive())) {
			String id = e instanceof PlayerEntity ? e.getName().getString().toLowerCase(Locale.ROOT)
					: Registries.ENTITY_TYPE.getId(e.getType()).getPath();
			if (!id.contains(n) && !n.contains(id)) continue;
			double d = e.squaredDistanceTo(bot);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	private static final String[][] CLAIM_WORDS = {
			{"поставил|поставила|ставлю|установил|установила|построил|построила|достроил|достроила", "place"},
			{"сломал|сломала|ломаю|разрушил|разрушила", "break,mine"},
			{"добыл|добыла|накопал|накопала|нарубил|нарубила|копаю|рублю", "mine,break"},
			{"ударил|ударила|бью|врезал|врезала|убил|убила", "attack,fight_back"},
			{"скрафтил|скрафтила|крафчу", "craft"},
			{"надел|надела|надеваю", "equip"},
			{"положил|положила|сложил|сложила|разложил|разложила", "store"},
			{"выбросил|выбросила|отдал|отдала|кинул|кинула", "give"},
			{"съел|съела|поел|поела", "eat"}};
	private static final Pattern[] CLAIM_PATTERNS = new Pattern[CLAIM_WORDS.length];

	static {
		for (int i = 0; i < CLAIM_WORDS.length; i++) {
			CLAIM_PATTERNS[i] = Pattern.compile("(?<![\\p{L}])(" + CLAIM_WORDS[i][0] + ")(?![\\p{L}])",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		}
	}

	/** Если в реплике заявлено дело, которого нет среди действий, вернёт заявленное слово. */
	private static String claimMismatch(String say, List<String> types) {
		for (int i = 0; i < CLAIM_WORDS.length; i++) {
			java.util.regex.Matcher m = CLAIM_PATTERNS[i].matcher(say);
			if (!m.find()) continue;
			boolean ok = false;
			for (String need : CLAIM_WORDS[i][1].split(",")) {
				if (types.contains(need)) ok = true;
			}
			if (!ok) return m.group(1);
		}
		return null;
	}

	private static String normalizeType(JsonObject a) {
		String t = str(a, "type").toLowerCase(Locale.ROOT).trim();
		switch (t) {
			case "break_block", "dig", "destroy", "remove_block", "break" -> t = "break";
			case "chop", "gather", "collect", "harvest", "mine_block", "mine" -> t = "mine";
			case "place_block", "put", "set_block", "placeblock", "build", "place" -> t = "place";
			case "wear", "armor", "put_on", "equip_armor", "equip" -> t = "equip";
			case "drop", "hand", "pass", "give_item", "give" -> t = "give";
			case "fight", "kill", "defend", "hit", "punch", "beat", "attack" -> t = "attack";
			case "fight_back", "fightback", "retaliate" -> t = "fight_back";
			case "flee", "run", "run_away", "escape" -> t = "flee";
			case "come_here", "go", "walk", "come" -> t = "come";
			case "follow_me", "follow" -> t = "follow";
			case "explore", "travel", "adventure", "wander", "look_around" -> t = "explore";
			case "go_home", "home" -> t = "home";
			case "sethome", "set_home" -> t = "set_home";
			case "store_all", "deposit", "put_in_chest", "store" -> t = "store";
			case "withdraw", "get_from_chest", "take" -> t = "take";
			case "open_chest", "look_in_chest", "check_chest", "check_chests" -> t = "check_chests";
			default -> {
				// как есть
			}
		}
		boolean hasPos = a.has("x") || a.has("dx");
		if (t.equals("mine") && hasPos) t = "break";
		if (t.equals("come") && hasPos) t = "goto";
		if (t.equals("break") && !hasPos && a.has("block") && !str(a, "block").isEmpty()) t = "mine";
		return t;
	}

	private static BlockPos posOf(JsonObject a, AveryPlayer bot) {
		if (a.has("x") && a.has("y") && a.has("z")) return new BlockPos(intOf(a, "x", 0), intOf(a, "y", 0), intOf(a, "z", 0));
		if (a.has("dx") || a.has("dy") || a.has("dz")) {
			return bot.getBlockPos().add(intOf(a, "dx", 0), intOf(a, "dy", 0), intOf(a, "dz", 0));
		}
		return null;
	}

	public static void perform(MinecraftServer server, AveryPlayer bot, @Nullable ServerPlayerEntity player, JsonObject a) {
		String type = normalizeType(a);
		switch (type) {
			case "explore" -> bot.startExplore();
			case "goto" -> {
				BlockPos gp = posOf(a, bot);
				if (gp == null) say(server, "Не понял, куда идти 🤔");
				else bot.startGoto(Vec3d.ofCenter(gp));
			}
			case "home" -> {
				if (homePos == null) say(server, "Я ещё не выбрал дом 🏠");
				else bot.startGoto(Vec3d.ofCenter(homePos));
			}
			case "set_home" -> {
				homePos = bot.getBlockPos();
				save();
			}
			case "store" -> bot.startChest("store", str(a, "item"), intOf(a, "count", 64));
			case "take" -> {
				if (str(a, "item").isBlank()) say(server, "Не понял, что брать из сундука 🤔");
				else bot.startChest("take", str(a, "item"), intOf(a, "count", 1));
			}
			case "check_chests" -> bot.startChest("check", "", 1);
			case "break" -> {
				BlockPos p = posOf(a, bot);
				if (p == null) p = bot.getBlockPos().offset(bot.getHorizontalFacing());
				bot.startBreak(List.of(p));
			}
			case "place" -> {
				Block pb = AveryPlayer.blockByName(str(a, "block"));
				if (pb == null) {
					say(server, "Не знаю такой блок: " + str(a, "block") + " 🤔");
				} else {
					BlockPos p = posOf(a, bot);
					List<BlockPos> list = new ArrayList<>();
					if (p != null) {
						list.add(p);
					} else {
						Direction f = bot.getHorizontalFacing();
						int n = Math.max(1, Math.min(intOf(a, "count", 1), 16));
						for (int i = 1; i <= n; i++) list.add(bot.getBlockPos().offset(f, i));
					}
					bot.startPlace(pb, list);
				}
			}
			case "follow" -> {
				if (player != null) bot.startFollow(player.getUuid());
			}
			case "come" -> {
				if (player != null) bot.startGoto(player.getPos());
			}
			case "stop" -> bot.stop();
			case "mine" -> {
				Block b = AveryPlayer.blockByName(str(a, "block"));
				if (b == null) say(server, "Не знаю такой блок: " + str(a, "block") + " 🤔");
				else bot.startMine(b, intOf(a, "count", 1));
			}
			case "craft" -> {
				Item it = AveryPlayer.itemByName(str(a, "item"));
				if (it == null) say(server, "Не знаю такой предмет: " + str(a, "item") + " 🤔");
				else report(bot, bot.craft(it, intOf(a, "count", 1)));
			}
			case "equip" -> {
				if (!bot.equipItem(str(a, "item"))) say(server, "Не могу это надеть: " + str(a, "item") + " 🤷");
			}
			case "hold" -> {
				if (!bot.holdItem(str(a, "item"))) say(server, "У меня нет: " + str(a, "item") + " 🤷");
			}
			case "give" -> {
				int n = bot.giveTo(str(a, "item"), intOf(a, "count", 1), player);
				if (n == 0) say(server, "У меня нет: " + str(a, "item") + " 🤷");
			}
			case "attack" -> {
				String tn = str(a, "target");
				if (tn.isBlank()) {
					bot.startAttack();
				} else {
					LivingEntity found = findTarget(bot, tn);
					if (found == null) {
						report(bot, "Не вижу цель «" + tn + "» рядом.");
					} else {
						bot.startFight(found, found instanceof PlayerEntity ? Math.max(1, Math.min(intOf(a, "count", 3), 10)) : 99);
					}
				}
			}
			case "fight_back" -> {
				LivingEntity t = bot.getLastAttacker();
				if (t != null && t.isAlive()) bot.startFight(t, t instanceof PlayerEntity ? 6 : 99);
			}
			case "flee" -> {
				LivingEntity t = bot.getLastAttacker();
				if (t != null && t.isAlive()) bot.startFlee(t);
			}
			case "eat" -> bot.eatNow();
			default -> {
				AveryMod.LOGGER.warn("Неизвестное действие Эвери: {}", a);
				if (AveryConfig.get().debug) say(server, "(не умею такое действие: " + type + ")");
			}
		}
	}

	// ---------- вывод в чат ----------

	public static void say(MinecraftServer server, String text) {
		String t = text.length() > 250 ? text.substring(0, 250) : text;
		server.getPlayerManager().broadcast(Text.literal("<" + AveryConfig.get().botName + "> " + t), false);
		com.avery.ai.voice.AveryVoice.speak(server, t);
	}
}
