package com.avery.ai.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** WAV <-> PCM и приведение звука к 48 кГц моно 16 бит (формат Simple Voice Chat). */
final class AveryAudio {
	private AveryAudio() {}

	static byte[] toWav(short[] pcm, int rate) {
		int dataLen = pcm.length * 2;
		ByteBuffer b = AveryVoice.le(44 + dataLen);
		b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
		b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes()).putInt(dataLen);
		for (short s : pcm) b.putShort(s);
		return b.array();
	}

	/** @return 48 кГц моно, или null, если формат не поддерживается (нужен WAV PCM 16 бит). */
	static short[] wavTo48kMono(byte[] wav) {
		if (wav == null || wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
			throw new RuntimeException("озвучка вернула не WAV (в настройках нужен сервис, отдающий wav)");
		}
		ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		int pos = 12;
		int channels = 1;
		int rate = 24000;
		int bits = 16;
		int format = 1;
		int dataPos = -1;
		int dataLen = 0;
		while (pos + 8 <= wav.length) {
			String id = new String(wav, pos, 4);
			int len = b.getInt(pos + 4);
			if (id.equals("fmt ")) {
				format = b.getShort(pos + 8) & 0xFFFF;
				channels = b.getShort(pos + 10);
				rate = b.getInt(pos + 12);
				bits = b.getShort(pos + 22);
			} else if (id.equals("data")) {
				dataPos = pos + 8;
				dataLen = len < 0 || dataPos + len > wav.length ? wav.length - dataPos : len;
				break;
			}
			pos += 8 + len + (len & 1);
		}
		if (dataPos < 0 || bits != 16 || (format != 1 && format != 0xFFFE) || channels < 1) {
			throw new RuntimeException("неподдерживаемый WAV (нужен PCM 16 бит)");
		}
		int frames = dataLen / (2 * channels);
		double[] mono = new double[frames];
		for (int i = 0; i < frames; i++) {
			int sum = 0;
			for (int ch = 0; ch < channels; ch++) sum += b.getShort(dataPos + (i * channels + ch) * 2);
			mono[i] = sum / (double) channels;
		}
		if (rate == 48000) {
			short[] out = new short[frames];
			for (int i = 0; i < frames; i++) out[i] = (short) mono[i];
			return out;
		}
		int outLen = (int) ((long) frames * 48000 / rate);
		short[] out = new short[outLen];
		double ratio = rate / 48000.0;
		for (int i = 0; i < outLen; i++) {
			double src = i * ratio;
			int i0 = (int) src;
			int i1 = Math.min(i0 + 1, frames - 1);
			double f = src - i0;
			out[i] = (short) (mono[i0] * (1 - f) + mono[i1] * f);
		}
		return out;
	}
}
