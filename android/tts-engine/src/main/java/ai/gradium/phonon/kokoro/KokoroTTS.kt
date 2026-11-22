package ai.gradium.phonon.kokoro

import android.content.Context
import android.util.Log
import com.local.kokorotts.NativeMisaki
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** Callback invoked on the synthesis thread with each streamed 24 kHz float PCM window. */
internal fun interface KokoroAudioCallback {
    fun onAudio(pcm: FloatArray): Boolean
}

internal object KokoroNative {
    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("kokoro_npu")
    }

    external fun nativeLoad(bundleDir: String, nativeLibDir: String): Long
    external fun nativeSetVoice(handle: Long, voiceName: String)
    external fun nativeStop(handle: Long)
    external fun nativeSpeak(handle: Long, tokenIds: LongArray, speed: Float, callback: KokoroAudioCallback): DoubleArray
    external fun nativeClose(handle: Long)
}

/**
 * Kokoro-82M hybrid NPU/CPU TTS engine for Galaxy S23 (SM8550):
 * Misaki + eSpeak NG G2P -> int8 ONNX front end (CPU) -> harmonic source (CPU) ->
 * QNN HTP vocoder_b32 / vocoder_b128 (NPU) -> iSTFT (CPU), streamed per window.
 */
class KokoroTTS private constructor(
    private var handle: Long,
    private val vocab: Map<Char, Long>,
    val voices: List<String>,
) : Closeable {
    data class Metrics(val samples: Long, val ttfaMs: Double, val totalMs: Double)

    val sampleRate: Int = 24000
    private var currentVoice: String = ""

    fun setVoice(name: String) {
        check(handle != 0L) { "KokoroTTS is closed" }
        KokoroNative.nativeSetVoice(handle, name)
        currentVoice = name
    }

    fun stop() {
        val h = handle
        if (h != 0L) KokoroNative.nativeStop(h)
    }

    fun speak(text: String, speed: Float = 1f, onPcm: (FloatArray) -> Boolean): Metrics {
        check(handle != 0L) { "KokoroTTS is closed" }
        val british = currentVoice.startsWith("bf_") || currentVoice.startsWith("bm_")
        val phonemes = NativeMisaki.phonemize(text, british)
        val chunks = chunkTokens(encode(phonemes))
        if (chunks.isEmpty()) return Metrics(0L, 0.0, 0.0)

        var totalSamples = 0L
        var firstTtfa = 0.0
        var totalMs = 0.0
        var stopped = false
        for (chunk in chunks) {
            if (stopped) break
            val ids = LongArray(chunk.size + 2)
            for (i in chunk.indices) ids[i + 1] = chunk[i]
            val res = KokoroNative.nativeSpeak(handle, ids, speed) { pcm ->
                val cont = onPcm(pcm)
                if (!cont) stopped = true
                cont
            }
            val samples = res[0].toLong()
            if (totalSamples == 0L && samples > 0L) firstTtfa = totalMs + res[1]
            totalSamples += samples
            totalMs += res[2]
            if (samples == 0L) break
        }
        return Metrics(totalSamples, firstTtfa, totalMs)
    }

    private fun encode(phonemes: String): List<Long> {
        val out = ArrayList<Long>(phonemes.length)
        for (c in phonemes) {
            val id = vocab[c]
            if (id != null && id != 0L) out.add(id)
        }
        return out
    }

    /** Split token sequences longer than MAX_PHONEMES (500) at spaces or punctuation so style_row stays in [0, 509]. */
    private fun chunkTokens(tokens: List<Long>): List<List<Long>> {
        if (tokens.isEmpty()) return emptyList()
        if (tokens.size <= MAX_PHONEMES) return listOf(tokens)
        val result = ArrayList<List<Long>>()
        var start = 0
        while (start < tokens.size) {
            val end = (start + MAX_PHONEMES).coerceAtMost(tokens.size)
            if (end == tokens.size) {
                result.add(tokens.subList(start, end))
                break
            }
            var split = end
            for (i in end - 1 downTo start + MAX_PHONEMES / 2) {
                if (tokens[i] in BREAK_TOKENS) {
                    split = i + 1
                    break
                }
            }
            result.add(tokens.subList(start, split))
            start = split
        }
        return result
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) KokoroNative.nativeClose(h)
    }

    companion object {
        private const val TAG = "KokoroNpu"
        private const val MAX_PHONEMES = 500
        // Punctuation and space token IDs in Kokoro's tokenizer.json: ; : , . ! ? — … space
        private val BREAK_TOKENS = setOf(1L, 2L, 3L, 4L, 5L, 6L, 9L, 10L, 16L)
        @Volatile private var espeakInitialized = false

        fun load(context: Context, dir: File): KokoroTTS {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            if (!espeakInitialized) {
                synchronized(this) {
                    if (!espeakInitialized) {
                        val espeakLib = File(nativeLibDir, "libespeak-ng.so").path
                        val espeakData = File(dir, "espeak-ng-data").path
                        val err = NativeMisaki.initialize(espeakLib, espeakData)
                        if (err != null) Log.w(TAG, "eSpeak NG fallback init failed (continuing with Misaki lexicon): $err")
                        else Log.i(TAG, "eSpeak NG fallback initialized")
                        espeakInitialized = true
                    }
                }
            }
            val vocabObj = JSONObject(File(dir, "tokenizer.json").readText())
                .getJSONObject("model").getJSONObject("vocab")
            val vocab = HashMap<Char, Long>(vocabObj.length())
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.length == 1) vocab[k[0]] = vocabObj.getLong(k)
            }
            val voiceFiles = File(dir, "voices").listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }
                ?.map { it.name.removeSuffix(".bin") }?.sorted().orEmpty()
            val handle = KokoroNative.nativeLoad(dir.path, nativeLibDir)
            check(handle != 0L) { "KokoroNative.nativeLoad returned 0" }
            return KokoroTTS(handle, vocab, voiceFiles)
        }
    }
}
