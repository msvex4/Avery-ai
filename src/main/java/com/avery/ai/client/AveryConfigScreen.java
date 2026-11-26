package com.avery.ai.client;

import com.avery.ai.AveryConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/** Окно настроек Avery AI (Cloth Config). Всё сохраняется в config/avery.json. */
public final class AveryConfigScreen {
	private AveryConfigScreen() {}

	public static Screen create(Screen parent) {
		AveryConfig c = AveryConfig.get();
		ConfigBuilder b = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.literal("Avery AI"))
				.setSavingRunnable(AveryConfig::save);
		ConfigEntryBuilder e = b.entryBuilder();

		ConfigCategory ai = b.getOrCreateCategory(Text.literal("Нейросеть"));
		ai.addEntry(e.startStrField(Text.literal("🔑 Ключ API"), c.apiKey)
				.setDefaultValue("")
				.setTooltip(Text.literal("Свой ключ. Для Groq: console.groq.com/keys"))
				.setSaveConsumer(v -> c.apiKey = v.trim()).build());
		ai.addEntry(e.startStrField(Text.literal("Адрес API (URL)"), c.apiUrl)
				.setDefaultValue("https://api.groq.com/openai/v1/chat/completions")
				.setTooltip(Text.literal("Любой OpenAI-совместимый адрес (Groq, OpenRouter и др.)"))
				.setSaveConsumer(v -> c.apiUrl = v.trim()).build());
		ai.addEntry(e.startStrField(Text.literal("Модель"), c.model)
				.setDefaultValue("openai/gpt-oss-120b")
				.setSaveConsumer(v -> c.model = v.trim()).build());
		ai.addEntry(e.startBooleanToggle(Text.literal("JSON-режим ответа"), c.jsonMode)
				.setDefaultValue(true)
				.setTooltip(Text.literal("Выключи, если выбранная модель/сервис ругается на response_format"))
				.setSaveConsumer(v -> c.jsonMode = v).build());
		ai.addEntry(e.startDoubleField(Text.literal("Температура (креативность)"), c.temperature)
				.setDefaultValue(0.7).setMin(0.0).setMax(2.0)
				.setSaveConsumer(v -> c.temperature = v).build());

		ConfigCategory bot = b.getOrCreateCategory(Text.literal("Эвери"));
		bot.addEntry(e.startStrField(Text.literal("Ник в игре (латиницей)"), c.botName)
				.setDefaultValue("Avery")
				.setTooltip(Text.literal("Применится при следующем появлении Эвери"))
				.setSaveConsumer(v -> c.botName = v.trim().isEmpty() ? "Avery" : v.trim()).build());
		bot.addEntry(e.startStrList(Text.literal("На какие имена отзывается"), c.aliases)
				.setDefaultValue(List.of("эвери", "avery"))
				.setSaveConsumer(v -> c.aliases = v).build());
		bot.addEntry(e.startBooleanToggle(Text.literal("Показывать действия в чате (отладка)"), c.debug)
				.setDefaultValue(true).setSaveConsumer(v -> c.debug = v).build());
		bot.addEntry(e.startBooleanToggle(Text.literal("Появляться при входе в мир"), c.autoSpawn)
				.setDefaultValue(true).setSaveConsumer(v -> c.autoSpawn = v).build());

		ConfigCategory ch = b.getOrCreateCategory(Text.literal("Характер"));
		ch.addEntry(e.startBooleanToggle(Text.literal("Самостоятельность (живёт своей жизнью)"), c.autonomy)
				.setDefaultValue(true)
				.setTooltip(Text.literal("Сам решает, чем заняться: добыча, исследование, сундуки, дом"))
				.setSaveConsumer(v -> c.autonomy = v).build());
		ch.addEntry(e.startIntField(Text.literal("Как часто принимает решения (секунды)"), c.autonomySeconds)
				.setDefaultValue(60).setMin(10).setMax(900)
				.setTooltip(Text.literal("Чем чаще, тем больше запросов к нейросети (лимиты бесплатных ключей!)"))
				.setSaveConsumer(v -> c.autonomySeconds = v).build());
		ch.addEntry(e.startIntSlider(Text.literal("Непредсказуемость"), c.unpredictability, 0, 100)
				.setDefaultValue(50)
				.setTooltip(Text.literal("0 = ровный характер, 100 = каждый раз по-разному, внезапные идеи"))
				.setSaveConsumer(v -> c.unpredictability = v).build());
		ch.addEntry(e.startBooleanToggle(Text.literal("Болтать самому, если я молчу"), c.idleChatter)
				.setDefaultValue(true).setSaveConsumer(v -> c.idleChatter = v).build());
		ch.addEntry(e.startBooleanToggle(Text.literal("Маты (грубая лексика)"), c.profanity)
				.setDefaultValue(false)
				.setTooltip(Text.literal("Для эмоций (упал, умер, злость), без оскорблений игрока"))
				.setSaveConsumer(v -> c.profanity = v).build());
		ch.addEntry(e.startBooleanToggle(Text.literal("Шутки и безобидные розыгрыши"), c.jokes)
				.setDefaultValue(true).setSaveConsumer(v -> c.jokes = v).build());
		ch.addEntry(e.startBooleanToggle(Text.literal("Привычки (утро, день, вечер, сон)"), c.habits)
				.setDefaultValue(true).setSaveConsumer(v -> c.habits = v).build());

		ConfigCategory voice = b.getOrCreateCategory(Text.literal("Голос"));
		voice.addEntry(e.startBooleanToggle(Text.literal("🎤 Слышать войс-чат"), c.voiceListen)
				.setDefaultValue(true)
				.setTooltip(Text.literal("Нужен мод Simple Voice Chat у всех игроков"))
				.setSaveConsumer(v -> c.voiceListen = v).build());
		voice.addEntry(e.startIntField(Text.literal("Радиус слуха (блоки)"), c.voiceRange)
				.setDefaultValue(24).setMin(2).setMax(128)
				.setSaveConsumer(v -> c.voiceRange = v).build());
		voice.addEntry(e.startStrField(Text.literal("🔑 Ключ распознавания (пусто = основной)"), c.sttKey)
				.setDefaultValue("").setSaveConsumer(v -> c.sttKey = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Адрес распознавания (URL)"), c.sttUrl)
				.setDefaultValue("https://api.groq.com/openai/v1/audio/transcriptions")
				.setSaveConsumer(v -> c.sttUrl = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Модель распознавания"), c.sttModel)
				.setDefaultValue("whisper-large-v3-turbo")
				.setSaveConsumer(v -> c.sttModel = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Язык речи (ru / en, пусто = авто)"), c.sttLanguage)
				.setDefaultValue("").setSaveConsumer(v -> c.sttLanguage = v.trim()).build());
		voice.addEntry(e.startBooleanToggle(Text.literal("🔊 Озвучка ответов"), c.ttsEnabled)
				.setDefaultValue(false)
				.setTooltip(Text.literal("Выключено: Эвери отвечает только в чат"))
				.setSaveConsumer(v -> c.ttsEnabled = v).build());
		voice.addEntry(e.startStrField(Text.literal("🔑 Ключ озвучки (пусто = основной)"), c.ttsKey)
				.setDefaultValue("").setSaveConsumer(v -> c.ttsKey = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Адрес озвучки (URL)"), c.ttsUrl)
				.setDefaultValue("https://api.openai.com/v1/audio/speech")
				.setTooltip(Text.literal("OpenAI-совместимый /audio/speech, который отдаёт WAV"))
				.setSaveConsumer(v -> c.ttsUrl = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Модель озвучки"), c.ttsModel)
				.setDefaultValue("tts-1").setSaveConsumer(v -> c.ttsModel = v.trim()).build());
		voice.addEntry(e.startStrField(Text.literal("Голос"), c.ttsVoice)
				.setDefaultValue("alloy").setSaveConsumer(v -> c.ttsVoice = v.trim()).build());

		return b.build();
	}
}
