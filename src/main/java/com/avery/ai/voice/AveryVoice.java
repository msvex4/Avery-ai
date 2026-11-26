package com.avery.ai.voice;

import com.avery.ai.AveryBrain;
import com.avery.ai.AveryConfig;
import com.avery.ai.AveryMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Голос Эвери: распознавание речи (STT) и озвучка (TTS) по твоим API-ключам.
 * Здесь НЕТ ссылок на Simple Voice Chat, поэтому класс безопасен, даже если его мода нет.
 * Всё сетевое идёт в отдельном потоке и не тормозит игру.
 */
public final class AveryVoice {
	/** Выставляется плагином Simple Voice Chat, когда он загрузился. */
	public static volatile boolean available;
	/** Проигрыватель 48 кГц моно, short[]; задаётся плагином. */
	public static volatile Consumer<short[]> speaker;

	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "avery-voice");
		t.setDaemon(true);
		return t;
	});

	private AveryVoice() {}

	// ---------- слух: PCM (48 кГц, моно) -> текст ----------

	public static void onHeard(MinecraftServer server, UUID playerId, short[] pcm) {
		AveryConfig c = AveryConfig.get();
		String key = (c.sttKey == null || c.sttKey.isBlank()) ? c.apiKey : c.sttKey;
		if (key == null || key.isBlank()) return;
		POOL.execute(() -> {
			try {
				String text = transcribe(key.trim(), c.sttUrl, c.sttModel, c.sttLanguage, AveryAudio.toWav(pcm, 48000));
				if (text != null && !text.isBlank()) {
					server.execute(() -> {
						ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerId);
						if (p != null) AveryBrain.onVoice(p, text.trim());
					});
				}
			} catch (Exception e) {
				AveryMod.LOGGER.warn("Ошибка распознавания голоса: {}", e.getMessage());
			}
		});
	}

	private static String transcribe(String key, String url, String model, String lang, byte[] wav) throws Exception {
		String boundary = "----avery" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		field(body, boundary, "model", model);
		field(body, boundary, "response_format", "json");
		if (lang != null && !lang.isBlank()) field(body, boundary, "language", lang.trim());
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpURLConnection c = open(url, key, "multipart/form-data; boundary=" + boundary);
		try (OutputStream os = c.getOutputStream()) {
			os.write(body.toByteArray());
		}
		byte[] resp = read(c);
		JsonObject o = JsonParser.parseString(new String(resp, StandardCharsets.UTF_8)).getAsJsonObject();
		return o.has("text") ? o.get("text").getAsString() : "";
	}

	private static void field(ByteArrayOutputStream b, String boundary, String name, String value) throws Exception {
		b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	// ---------- голос: текст -> звук в войс-чате ----------

	public static void speak(MinecraftServer server, String text) {
		AveryConfig c = AveryConfig.get();
		Consumer<short[]> sp = speaker;
		if (!available || sp == null || !c.ttsEnabled) return;
		String key = (c.ttsKey == null || c.ttsKey.isBlank()) ? c.apiKey : c.ttsKey;
		if (key == null || key.isBlank()) return;
		// убираем эмодзи и прочие символы, которые озвучка читает странно
		String clean = text.replaceAll("[\\p{So}\\p{Cs}\\p{Cn}]", "").replaceAll("\\s+", " ").trim();
		if (clean.isEmpty()) return;
		POOL.execute(() -> {
			try {
				JsonObject j = new JsonObject();
				j.addProperty("model", c.ttsModel);
				j.addProperty("input", clean);
				j.addProperty("voice", c.ttsVoice);
				j.addProperty("response_format", "wav");
				HttpURLConnection con = open(c.ttsUrl, key.trim(), "application/json");
				try (OutputStream os = con.getOutputStream()) {
					os.write(j.toString().getBytes(StandardCharsets.UTF_8));
				}
				byte[] wav = read(con);
				short[] pcm = AveryAudio.wavTo48kMono(wav);
				if (pcm != null && pcm.length > 0) sp.accept(pcm);
			} catch (Exception e) {
				AveryMod.LOGGER.warn("Ошибка озвучки: {}", e.getMessage());
			}
		});
	}

	// ---------- http ----------

	private static HttpURLConnection open(String url, String key, String contentType) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod("POST");
		c.setConnectTimeout(10_000);
		c.setReadTimeout(40_000);
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", contentType);
		c.setRequestProperty("Authorization", "Bearer " + key);
		return c;
	}

	private static byte[] read(HttpURLConnection c) throws Exception {
		int code = c.getResponseCode();
		InputStream is = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
		byte[] data = is == null ? new byte[0] : is.readAllBytes();
		if (code / 100 != 2) {
			String s = new String(data, StandardCharsets.UTF_8);
			throw new RuntimeException("HTTP " + code + " " + (s.length() > 200 ? s.substring(0, 200) : s));
		}
		return data;
	}

	/** Вспомогательное: little-endian буфер. */
	static ByteBuffer le(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}
}
