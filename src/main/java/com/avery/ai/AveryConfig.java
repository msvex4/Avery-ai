package com.avery.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Настройки мода. Файл: config/avery.json (ключ API вставляется сюда или командой /avery key). */
public class AveryConfig {
	public String apiKey = "";
	public String apiUrl = "https://api.groq.com/openai/v1/chat/completions";
	public String model = "openai/gpt-oss-120b";
	public boolean jsonMode = true;
	public double temperature = 0.7;

	/** Ник бота в игре (латиницей, до 16 символов). */
	public String botName = "Avery";
	/** На какие слова в чате откликается Эвери. */
	public List<String> aliases = new ArrayList<>(List.of("эвери", "avery"));

	// Голос: слух (распознавание речи). Пустой ключ = берётся основной apiKey.
	public boolean voiceListen = true;
	public int voiceRange = 24;
	public String sttKey = "";
	public String sttUrl = "https://api.groq.com/openai/v1/audio/transcriptions";
	public String sttModel = "whisper-large-v3-turbo";
	/** Язык речи: ru, en ... Пусто = определять автоматически. */
	public String sttLanguage = "";

	// Голос: озвучка ответов. Любой сервис, совместимый с OpenAI /audio/speech и отдающий WAV.
	public boolean ttsEnabled = false;
	public String ttsKey = "";
	public String ttsUrl = "https://api.openai.com/v1/audio/speech";
	public String ttsModel = "tts-1";
	public String ttsVoice = "alloy";

	/** Показывать в чате, какие действия выбрала нейросеть (для отладки). */
	public boolean debug = true;

	// Характер и самостоятельность
	public boolean autonomy = true;
	public int autonomySeconds = 60;
	/** 0..100: насколько он непредсказуем (разброс настроения, идей и ответов). */
	public int unpredictability = 50;
	public boolean profanity = false;
	public boolean jokes = true;
	public boolean habits = true;

	public boolean autoSpawn = true;
	public boolean idleChatter = true;
	public int idleChatterSeconds = 150;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("avery.json");
	private static AveryConfig instance;

	public static AveryConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				instance = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), AveryConfig.class);
			}
		} catch (Exception e) {
			AveryMod.LOGGER.warn("Не удалось прочитать avery.json, беру настройки по умолчанию", e);
		}
		if (instance == null) instance = new AveryConfig();
		if (instance.sttKey == null) instance.sttKey = "";
		if (instance.ttsKey == null) instance.ttsKey = "";
		if (instance.aliases == null) instance.aliases = new ArrayList<>(List.of("эвери", "avery"));
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AveryMod.LOGGER.warn("Не удалось сохранить avery.json", e);
		}
	}
}
