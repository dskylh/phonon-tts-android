package ai.gradium.phonon.tts

import ai.gradium.phonon.kokoro.KokoroTTS
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Kokoro-82M on the Snapdragon NPU (Qualcomm HTP v73 vocoder + int8 ONNX CPU front end),
 * compiled for SoC SM8550 (Galaxy S23). English only, 28 voices (Heart, female, by default).
 *
 * Every native call holds [lock]: the engine allows one speak at a time and no close during one.
 */
object Kokoro {
    private const val TAG = "KokoroNpu"
    const val SOC = "SM8550"
    private const val BUNDLE = "kokoro-s23-v1"
    // Pinned: a new bundle gets a new release tag, URL and hash.
    private const val URL_ZIP =
        "https://github.com/dskylh/phonon-tts-android/releases/download/kokoro-npu-s23-v1/kokoro-npu-s23-bundle-v1.zip"
    private const val ZIP_SIZE = 96_053_206L
    const val SIZE_LABEL = "96 MB"
    private const val ZIP_SHA256 = "6587add197f47049516984b602f43ef012d0b49628e84460a268fcbac066684f"
    private const val UNPACKED_LIMIT = 200L * 1024 * 1024
    private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val REQUIRED = listOf(
        "metadata.json",
        "kokoro.bin",
        "front-q8.onnx",
        "source-full.onnx",
        "istft-fp32.onnx",
        "tokenizer.json",
        "espeak-ng-data/phontab",
    )
    /** Must be female: used when nothing is saved or the saved voice is gone. */
    const val DEFAULT_VOICE = "af_heart"

    data class Voice(val id: String, val name: String, val gender: String?, val locale: String?) {
        val group get() = when (gender) { "female" -> "Female"; "male" -> "Male"; else -> "Other" }
        val accent get() = when (locale) { "en-us" -> "American"; "en-gb" -> "British"; else -> null }
    }

    val lock = Any()
    @Volatile private var engine: KokoroTTS? = null
    /** Why Kokoro is unusable in this process, after a load or speak failure. */
    @Volatile var failure: String? = null; private set
    @Volatile var lastLoadMs: Double = 0.0; private set
    @Volatile private var activeVoice: String? = null
    @Volatile private var catalog: Pair<File, List<Voice>>? = null

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.uppercase(Locale.ROOT) == SOC
    fun dir(context: Context) = File(context.filesDir, "kokoro/$BUNDLE")
    fun installed(context: Context) = REQUIRED.all { File(dir(context), it).isFile } && voices(context).isNotEmpty()

    /** The bundle's voices whose voice & stats files are present, female first, then male. */
    fun voices(context: Context): List<Voice> {
        val dir = dir(context)
        catalog?.takeIf { it.first == dir }?.let { return it.second }
        val list = readVoices(dir)
        if (list.isNotEmpty()) catalog = dir to list
        return list
    }

    private fun readVoices(dir: File): List<Voice> = try {
        val array = JSONObject(File(dir, "metadata.json").readText()).getJSONArray("voices")
        val order = listOf("Female", "Male", "Other")
        List(array.length()) { array.getJSONObject(it) }.mapNotNull { v ->
            val file = v.optString("file")
            val stats = v.optString("stats")
            if (file.isEmpty() || file.startsWith("/") || ".." in file.split('/') || !File(dir, file).isFile) return@mapNotNull null
            if (stats.isEmpty() || stats.startsWith("/") || ".." in stats.split('/') || !File(dir, stats).isFile) return@mapNotNull null
            Voice(
                id = v.getString("id"),
                name = v.getString("name"),
                gender = v.optString("gender").takeIf { !v.isNull("gender") && it.isNotEmpty() },
                locale = v.optString("locale").takeIf { !v.isNull("locale") && it.isNotEmpty() },
            )
        }.sortedBy { order.indexOf(it.group) }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the Kokoro voices", e)
        emptyList()
    }

    /** The saved Kokoro voice if the bundle has it, else af_heart, else the first female voice. Never a male fallback. */
    fun voice(context: Context): String {
        val voices = voices(context)
        val ids = voices.map { it.id }
        return prefs(context).getString("voice", null)?.takeIf { it in ids }
            ?: DEFAULT_VOICE.takeIf { it in ids }
            ?: voices.firstOrNull { it.gender == "female" }?.id
            ?: DEFAULT_VOICE
    }

    fun voiceDisplayName(context: Context, id: String = voice(context)): String =
        voices(context).firstOrNull { it.id == id }?.name ?: id

    fun selectVoice(context: Context, id: String) {
        prefs(context).edit().putString("voice", id).apply()
    }

    /** Switch the loaded engine to [id] if it is not already using it. Caller holds [lock]. */
    fun applyVoice(tts: KokoroTTS, id: String) {
        if (activeVoice == id) return
        val target = if (id in tts.voices) id else tts.voices.firstOrNull { it == DEFAULT_VOICE } ?: tts.voices.first()
        tts.setVoice(target)
        activeVoice = target
    }

    private fun prefs(context: Context) = context.getSharedPreferences("kokoro", Context.MODE_PRIVATE)

    /** Whether the system engine should try Kokoro for [language]. */
    fun wanted(context: Context, language: String) =
        language == "en" && supported() && Npu.engine(context) == SpeechEngine.KOKORO_NPU && installed(context) && failure == null

    /** The loaded engine, loading it on first use. Caller holds [lock]. Null if it cannot load. */
    fun get(context: Context): KokoroTTS? {
        engine?.let { return it }
        if (!supported() || !installed(context) || failure != null) return null
        return try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val tts = KokoroTTS.load(context.applicationContext, dir(context))
            lastLoadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            activeVoice = null
            applyVoice(tts, voice(context))
            Log.i(TAG, "Kokoro NPU engine loaded in ${lastLoadMs.toInt()} ms, ${tts.voices.size} voices, using $activeVoice")
            engine = tts
            tts
        } catch (t: Throwable) {
            fail("load", t)
            null
        }
    }

    /**
     * Run [work] with Kokoro under [lock], or return null when Kokoro is not available so
     * the caller falls back to CPU. A failure inside [work] disables Kokoro for this process and rethrows.
     */
    fun <T> use(context: Context, work: (KokoroTTS) -> T): T? = synchronized(lock) {
        val tts = get(context) ?: return null
        try {
            applyVoice(tts, voice(context))
            work(tts)
        } catch (t: Throwable) {
            fail("speak", t)
            throw t
        }
    }

    private fun fail(stage: String, t: Throwable) {
        Log.e(TAG, "Kokoro $stage failed; using the CPU engine", t)
        failure = "$stage: ${t.javaClass.simpleName}: ${t.message}"
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        activeVoice = null
    }

    /** Close the engine and allow a fresh load (also clears a previous failure). */
    fun release() = synchronized(lock) {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        activeVoice = null
        failure = null
    }

    fun delete(context: Context) = synchronized(lock) {
        release()
        dir(context).deleteRecursively()
        catalog = null
    }

    /** Download the pinned bundle, check its size and SHA-256, and unpack it. Blocks; call off the main thread. */
    fun download(context: Context, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val root = File(context.filesDir, "kokoro").apply { mkdirs() }
        require(root.usableSpace > ZIP_SIZE + UNPACKED_LIMIT) { "Not enough storage (needs about 300 MB free)" }
        val zip = File(root, "$BUNDLE.zip.part")
        val staging = File(root, "$BUNDLE.staging")
        try {
            fetch(zip, cancelled, progress)
            if (cancelled()) throw CancellationException()
            unpack(zip, staging, cancelled)
            check(REQUIRED.all { File(staging, it).isFile } && readVoices(staging).any { it.id == DEFAULT_VOICE }) {
                "Bundle is missing files"
            }
            synchronized(lock) {
                release()
                val target = dir(context)
                target.deleteRecursively()
                check(staging.renameTo(target)) { "Cannot install the bundle" }
                catalog = null
            }
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun validate(url: String): URI = URI(url).also {
        require(
            it.scheme == "https" && it.host in HOSTS && it.rawUserInfo == null &&
                (it.port == -1 || it.port == 443) && it.fragment == null
        ) { "Unsafe download redirect: ${it.host}" }
    }

    private fun fetch(into: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        var url = URL_ZIP
        for (hop in 0..5) {
            if (cancelled()) throw CancellationException()
            validate(url)
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 30_000
                conn.setRequestProperty("Accept-Encoding", "identity")
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 5) { "Too many redirects" }
                    val location = conn.getHeaderField("Location") ?: error("Missing redirect")
                    url = validate(url).resolve(location).toString()
                    continue
                }
                require(status == 200) { "Download HTTP $status. Check the network and retry." }
                require(conn.contentLengthLong == -1L || conn.contentLengthLong == ZIP_SIZE) { "Unexpected download size" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(into).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw CancellationException()
                            val n = input.read(buffer)
                            if (n == -1) break
                            count += n
                            require(count <= ZIP_SIZE) { "Download exceeds the expected size" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            progress(count, ZIP_SIZE)
                        }
                        output.fd.sync()
                    }
                }
                require(count == ZIP_SIZE) { "Incomplete download" }
                require(DownloadPolicy.hex(digest.digest()) == ZIP_SHA256) { "Bundle integrity check failed" }
                return
            } finally {
                conn.disconnect()
            }
        }
        error("Too many redirects")
    }

    /** Unzip into [into], rejecting paths that leave it. Skips lib/: the app never loads downloaded code. */
    private fun unpack(zip: File, into: File, cancelled: () -> Boolean) {
        into.deleteRecursively()
        check(into.mkdirs())
        val base = into.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (cancelled()) throw CancellationException()
                val name = entry.name
                require(!name.startsWith("/") && '\\' !in name && name.split('/').none { it == ".." }) { "Unsafe path in bundle" }
                if (entry.isDirectory || name.startsWith("lib/")) continue
                val out = File(into, name)
                require(out.canonicalPath.startsWith(base)) { "Unsafe path in bundle" }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        total += n
                        require(total <= UNPACKED_LIMIT) { "Bundle too large when unpacked" }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    fun status(context: Context): String = when {
        Build.VERSION.SDK_INT < 31 -> "Needs Android 12 or newer."
        !supported() -> "Kokoro NPU is compiled for $SOC only; this phone is ${Build.SOC_MODEL}."
        !installed(context) -> "Kokoro NPU model not downloaded ($SIZE_LABEL)."
        failure != null -> "Kokoro NPU failed, using CPU: $failure"
        engine != null -> "Kokoro NPU loaded (${lastLoadMs.toInt()} ms), voice ${voiceDisplayName(context)}."
        else -> "Kokoro NPU model downloaded."
    }
}
