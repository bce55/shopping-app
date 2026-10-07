package com.vntranscriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes .opus (Ogg/Opus, what WhatsApp uses) or .m4a to the format Whisper wants:
 * 16 kHz, mono, float in [-1, 1]. Uses the platform decoders, so no FFmpeg needed.
 */
object AudioDecoder {
    const val WHISPER_RATE = 16_000
    private const val MAX_SECONDS = 15 * 60 // ~58 MB of floats. Longer notes are cut.
    private const val TIMEOUT_US = 10_000L

    fun decodeToWhisperPcm(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            error("No audio track in ${file.name}")
        }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false

        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        val mono = FloatList()
        try {
            codec.configure(inFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.order(ByteOrder.nativeOrder())
                    if (floatPcm) {
                        val fb = out.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += fb.get() }
                            mono.add(sum / channels)
                        }
                    } else {
                        val sb = out.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += sb.get() / 32768f }
                            mono.add(sum / channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (mono.size > MAX_SECONDS * sampleRate) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return resample(mono.toArray(), sampleRate, WHISPER_RATE)
    }

    /**
     * Box-filter decimation. Averaging each output window acts as a cheap
     * low-pass, so 48 kHz -> 16 kHz does not alias. Good enough for speech.
     */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return src
        val ratio = from.toDouble() / to
        val out = FloatArray((src.size / ratio).toInt())
        for (i in out.indices) {
            val start = (i * ratio).toInt()
            val end = minOf(((i + 1) * ratio).toInt().coerceAtLeast(start + 1), src.size)
            var sum = 0f
            for (j in start until end) sum += src[j]
            out[i] = sum / (end - start)
        }
        return out
    }

    private class FloatList {
        private var data = FloatArray(16_000 * 30)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
