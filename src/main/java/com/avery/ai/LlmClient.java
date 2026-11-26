package com.avery.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Запросы к любому OpenAI-совместимому API (Groq, OpenRouter, Gemini-compat и т.д.).
 * Работает в отдельном потоке, поэтому игра не подвисает.
 * Нарочно используется HttpURLConnection (java.base), а не java.net.http:
 * в урезанных Java-сборках лаунчеров на телефоне модуля java.net.http может не быть.
 */
public final class LlmClient {
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "avery-llm");
		t.setDaemon(true);
		return t;
	});

	private LlmClient() {}

	public static CompletableFuture<String> chat(JsonArray messages) {
		AveryConfig cfg = AveryConfig.get();
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model);
		body.add("messages", messages);
		body.addProperty("temperature", Math.min(1.5, cfg.temperature + cfg.unpredictability * 0.006));
		body.addProperty("max_tokens", 2000);
		if (cfg.jsonMode) {
			JsonObject rf = new JsonObject();
			rf.addProperty("type", "json_object");
			body.add("response_format", rf);
		}
		String url = cfg.apiUrl;
		String key = cfg.apiKey.trim();
		String payload = body.toString();

		return CompletableFuture.supplyAsync(() -> {
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
				c.setRequestMethod("POST");
				c.setConnectTimeout(10_000);
				c.setReadTimeout(25_000);
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				c.setRequestProperty("Authorization", "Bearer " + key);
				try (OutputStream os = c.getOutputStream()) {
					os.write(payload.getBytes(StandardCharsets.UTF_8));
				}
				int code = c.getResponseCode();
				InputStream is = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
				String text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
				if (code / 100 != 2) {
					throw new RuntimeException("HTTP " + code + " " + (text.length() > 200 ? text.substring(0, 200) : text));
				}
				JsonObject o = JsonParser.parseString(text).getAsJsonObject();
				return o.getAsJsonArray("choices").get(0).getAsJsonObject()
						.getAsJsonObject("message").get("content").getAsString();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, POOL);
	}
}
