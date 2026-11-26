package com.avery.ai.voice;

import com.avery.ai.AveryConfig;
import com.avery.ai.AveryMod;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мост к Simple Voice Chat:
 *  - слышит речь игроков рядом с Эвери и отдаёт её на распознавание;
 *  - проигрывает голос Эвери (озвучка) как звук от его тела.
 * Загружается только если Simple Voice Chat установлен.
 */
public class AveryVoicePlugin implements VoicechatPlugin {
	private static final int MAX_SAMPLES = 48000 * 15;
	private static final int MIN_SAMPLES = (int) (48000 * 0.45);
	private static final long SILENCE_MS = 700;

	private static class Stream {
		final OpusDecoder decoder;
		short[] buf = new short[48000 * 4];
		int len;
		long lastMs;

		Stream(OpusDecoder d) {
			decoder = d;
		}
	}

	private volatile VoicechatServerApi api;
	private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();

	@Override
	public String getPluginId() {
		return AveryMod.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi voicechatApi) {
		AveryVoice.available = true;
		AveryVoice.speaker = this::play;
		AveryMod.LOGGER.info("Simple Voice Chat найден: голос Эвери включён");
	}

	@Override
	public void registerEvents(EventRegistration reg) {
		reg.registerEvent(VoicechatServerStartedEvent.class, e -> api = e.getVoicechat());
		reg.registerEvent(MicrophonePacketEvent.class, this::onMic);
		ServerTickEvents.END_SERVER_TICK.register(this::flush);
	}

	// ---------- слух ----------

	private void onMic(MicrophonePacketEvent e) {
		VoicechatServerApi a = api;
		VoicechatConnection con = e.getSenderConnection();
		AveryConfig cfg = AveryConfig.get();
		if (a == null || con == null || !cfg.voiceListen || AveryMod.bot == null) return;
		if (!(con.getPlayer().getPlayer() instanceof ServerPlayerEntity p) || p instanceof com.avery.ai.AveryPlayer) return;
		if (p.getServerWorld() != AveryMod.bot.getServerWorld()
				|| p.squaredDistanceTo(AveryMod.bot) > cfg.voiceRange * cfg.voiceRange) return;

		byte[] opus = e.getPacket().getOpusEncodedData();
		Stream s = streams.computeIfAbsent(p.getUuid(), k -> new Stream(a.createDecoder()));
		synchronized (s) {
			if (opus == null || opus.length == 0) {
				s.lastMs = 0; // конец речи, отправим на ближайшем тике
				return;
			}
			short[] pcm = s.decoder.decode(opus);
			if (s.len + pcm.length > MAX_SAMPLES) return;
			if (s.len + pcm.length > s.buf.length) s.buf = Arrays.copyOf(s.buf, Math.min(MAX_SAMPLES, s.buf.length * 2));
			System.arraycopy(pcm, 0, s.buf, s.len, pcm.length);
			s.len += pcm.length;
			s.lastMs = System.currentTimeMillis();
		}
	}

	private void flush(MinecraftServer server) {
		if (server.getTicks() % 5 != 0) return;
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Stream> en : streams.entrySet()) {
			Stream s = en.getValue();
			short[] out = null;
			synchronized (s) {
				if (s.len == 0) continue;
				boolean ended = s.lastMs == 0 || now - s.lastMs > SILENCE_MS || s.len >= MAX_SAMPLES - 960;
				if (!ended) continue;
				if (s.len >= MIN_SAMPLES) out = Arrays.copyOf(s.buf, s.len);
				s.len = 0;
				s.lastMs = 0;
				s.decoder.resetState();
			}
			if (out != null) AveryVoice.onHeard(server, en.getKey(), out);
		}
	}

	// ---------- голос ----------

	private volatile AudioPlayer current;

	private void play(short[] pcm) {
		VoicechatServerApi a = api;
		if (a == null || AveryMod.bot == null) return;
		try {
			AudioPlayer old = current;
			if (old != null) old.stopPlaying();
			EntityAudioChannel ch = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(AveryMod.bot));
			if (ch == null) return;
			OpusEncoder enc = a.createEncoder();
			int[] pos = {0};
			AudioPlayer p = a.createAudioPlayer(ch, enc, () -> {
				if (pos[0] >= pcm.length) {
					enc.close();
					return null;
				}
				short[] frame = new short[960];
				int n = Math.min(960, pcm.length - pos[0]);
				System.arraycopy(pcm, pos[0], frame, 0, n);
				pos[0] += 960;
				return frame;
			});
			current = p;
			p.startPlaying();
		} catch (Exception ex) {
			AveryMod.LOGGER.warn("Не удалось проиграть голос Эвери: {}", ex.getMessage());
		}
	}
}
