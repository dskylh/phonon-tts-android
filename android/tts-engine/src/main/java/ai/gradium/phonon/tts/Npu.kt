package ai.gradium.phonon.tts

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import ai.gradium.phonon.PhononTTS as NpuTTS

/** Which synthesis engine handles English speech in [PhononService] and [BenchmarkActivity]. */
enum class SpeechEngine { CPU, PHONON_NPU, KOKORO_NPU }

/**
 * Phonon on the Snapdragon NPU (Qualcomm HTP), for the one phone this bundle was compiled for:
 * SoC SM8550 (Galaxy S23). English only, with the bundle's voices (Zoey, female, by default). Everywhere else, and whenever loading fails,
 * speech stays on the CPU engine.
 *
 * Every native NPU call holds [lock]: the engine allows one speak at a time and no close during one.
 */
object Npu {
    private const val TAG = "PhononNpu"
    const val SOC = "SM8550"
    private const val BUNDLE = "npu-s23-v2"
    private val OLD_BUNDLES = listOf("npu-s23-v1")
    // Pinned: a new bundle gets a new release tag, URL and hash.
    private const val URL_ZIP =
        "https://github.com/dskylh/phonon-tts-android/releases/download/npu-bundle-s23-v2/phonon-npu-s23-bundle-v2.zip"
    private const val ZIP_SIZE = 120_863_862L
    const val SIZE_LABEL = "121 MB"
    private const val ZIP_SHA256 = "40f45abf0794dcea0903a1b7fa49789a549e31cb78d94b637a46905e757e96c0"
    private const val UNPACKED_LIMIT = 200L * 1024 * 1024
    private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val REQUIRED = listOf("metadata.json", "phonon-sm8550.bin", "tokenizer.json", "bos.bin")
    /** Must be female: used when nothing is saved or the saved voice is gone. */
    const val DEFAULT_VOICE = "Zoey"

    /** A voice from the bundle's metadata.json. [gender] is null for character voices. */
    data class Voice(val name: String, val gender: String?, val category: String, val locale: String?, val description: String) {
        val group get() = when { category == "character" -> "Character"; gender == "female" -> "Female"; gender == "male" -> "Male"; else -> "Other" }
        val accent get() = when (locale) { "en-us" -> "American"; "en-gb" -> "British"; else -> null }
    }

    val lock = Any()
    @Volatile private var engine: NpuTTS? = null
    /** Why the NPU is unusable in this process, after a load or speak failure. */
    @Volatile var failure: String? = null; private set
    @Volatile var lastLoadMs: Double = 0.0; private set
    @Volatile private var activeVoice: String? = null
    @Volatile private var catalog: Pair<File, List<Voice>>? = null

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.uppercase(Locale.ROOT) == SOC
    fun dir(context: Context) = File(context.filesDir, "phonon/$BUNDLE")
    fun installed(context: Context) = REQUIRED.all { File(dir(context), it).isFile } && voices(context).isNotEmpty()
    /** An older bundle is on the phone but not the current one: the user has to download again. */
    fun outdated(context: Context) = !installed(context) && OLD_BUNDLES.any { File(context.filesDir, "phonon/$it").isDirectory }

    /** The bundle's voices whose files are present, female first, then male, then characters. */
    fun voices(context: Context): List<Voice> {
        val dir = dir(context)
        catalog?.takeIf { it.first == dir }?.let { return it.second }
        val list = readVoices(dir)
        if (list.isNotEmpty()) catalog = dir to list
        return list
    }

    private fun readVoices(dir: File): List<Voice> = try {
        val array = JSONObject(File(dir, "metadata.json").readText()).getJSONArray("voices")
        val order = listOf("Female", "Male", "Character", "Other")
        List(array.length()) { array.getJSONObject(it) }.mapNotNull { v ->
            val file = v.optString("file")
            if (file.isEmpty() || file.startsWith("/") || ".." in file.split('/') || !File(dir, file).isFile) return@mapNotNull null
            Voice(v.getString("name"), v.optString("gender").takeIf { !v.isNull("gender") && it.isNotEmpty() },
                v.optString("category", "standard"), v.optString("locale").takeIf { !v.isNull("locale") && it.isNotEmpty() },
                v.optString("description"))
        }.sortedBy { order.indexOf(it.group) }  // stable: keeps the bundle's order inside a group
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the NPU voices", e)
        emptyList()
    }

    /** The saved NPU voice if the bundle has it, else Zoey, else the first female voice. Never a male fallback. */
    fun voice(context: Context): String {
        val voices = voices(context)
        val names = voices.map { it.name }
        return prefs(context).getString("voice", null)?.takeIf { it in names }
            ?: DEFAULT_VOICE.takeIf { it in names }
            ?: voices.firstOrNull { it.gender == "female" }?.name
            ?: DEFAULT_VOICE
    }
    fun selectVoice(context: Context, name: String) {
        prefs(context).edit().putString("voice", name).apply()
    }

    /** Switch the loaded engine to [name] if it is not already using it. Caller holds [lock]. */
    fun applyVoice(tts: NpuTTS, name: String) {
        if (activeVoice == name) return
        if (name in tts.voices) tts.setVoice(name)
        else tts.voices.firstOrNull { it == DEFAULT_VOICE }?.let { tts.setVoice(it) }
        activeVoice = name
    }

    private fun prefs(context: Context) = context.getSharedPreferences("npu", Context.MODE_PRIVATE)
    fun engine(context: Context): SpeechEngine {
        val p = prefs(context)
        return when (p.getString("engine", null)) {
            "phonon_npu" -> SpeechEngine.PHONON_NPU
            "kokoro_npu" -> SpeechEngine.KOKORO_NPU
            "cpu" -> SpeechEngine.CPU
            else -> if (p.getBoolean("enabled", false)) SpeechEngine.PHONON_NPU else SpeechEngine.CPU
        }
    }
    fun setEngine(context: Context, choice: SpeechEngine) {
        val key = when (choice) {
            SpeechEngine.CPU -> "cpu"
            SpeechEngine.PHONON_NPU -> "phonon_npu"
            SpeechEngine.KOKORO_NPU -> "kokoro_npu"
        }
        prefs(context).edit()
            .putString("engine", key)
            .putBoolean("enabled", choice == SpeechEngine.PHONON_NPU)
            .apply()
        if (choice != SpeechEngine.PHONON_NPU) release()
        if (choice != SpeechEngine.KOKORO_NPU) Kokoro.release()
    }
    fun enabled(context: Context) = engine(context) == SpeechEngine.PHONON_NPU
    fun setEnabled(context: Context, on: Boolean) =
        setEngine(context, if (on) SpeechEngine.PHONON_NPU else SpeechEngine.CPU)

    /** Whether the system engine should try the NPU for [language]. */
    fun wanted(context: Context, language: String) =
        language == "en" && supported() && enabled(context) && installed(context) && failure == null

    /** The loaded engine, loading it on first use. Caller holds [lock]. Null if it cannot load. */
    fun get(context: Context): NpuTTS? {
        engine?.let { return it }
        if (!supported() || !installed(context) || failure != null) return null
        return try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val tts = NpuTTS.load(context.applicationContext, dir(context), "en")
            lastLoadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            activeVoice = null
            applyVoice(tts, voice(context))
            Log.i(TAG, "NPU engine loaded in ${lastLoadMs.toInt()} ms, ${tts.voices.size} voices, using $activeVoice")
            engine = tts
            tts
        } catch (t: Throwable) {
            fail("load", t)
            null
        }
    }

    /**
     * Run [work] with the NPU engine under [lock], or return null when the NPU is not available so
     * the caller uses the CPU. A failure inside [work] disables the NPU for this process and rethrows.
     */
    fun <T> use(context: Context, work: (NpuTTS) -> T): T? = synchronized(lock) {
        val tts = get(context) ?: return null
        try { applyVoice(tts, voice(context)); work(tts) } catch (t: Throwable) {
            fail("speak", t)
            throw t
        }
    }

    private fun fail(stage: String, t: Throwable) {
        Log.e(TAG, "NPU $stage failed; using the CPU engine", t)
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
        deleteOld(context)
        catalog = null
    }

    private fun deleteOld(context: Context) = OLD_BUNDLES.forEach { File(context.filesDir, "phonon/$it").deleteRecursively() }

    /** Download the pinned bundle, check its size and SHA-256, and unpack it. Blocks; call off the main thread. */
    fun download(context: Context, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val root = File(context.filesDir, "phonon").apply { mkdirs() }
        deleteOld(context)
        require(root.usableSpace > ZIP_SIZE + UNPACKED_LIMIT) { "Not enough storage (needs about 330 MB free)" }
        val zip = File(root, "$BUNDLE.zip.part")
        val staging = File(root, "$BUNDLE.staging")
        try {
            fetch(zip, cancelled, progress)
            if (cancelled()) throw CancellationException()
            unpack(zip, staging, cancelled)
            check(REQUIRED.all { File(staging, it).isFile } && readVoices(staging).any { it.name == DEFAULT_VOICE }) { "Bundle is missing files" }
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
        require(it.scheme == "https" && it.host in HOSTS && it.rawUserInfo == null &&
            (it.port == -1 || it.port == 443) && it.fragment == null) { "Unsafe download redirect: ${it.host}" }
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
            } finally { conn.disconnect() }
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
        !supported() -> "This NPU model is compiled for $SOC only; this phone is ${Build.SOC_MODEL}."
        outdated(context) -> "A newer NPU model with 37 voices is available. Download it to keep using the NPU ($SIZE_LABEL)."
        !installed(context) -> "NPU model not downloaded ($SIZE_LABEL)."
        failure != null -> "NPU failed, using CPU: $failure"
        engine != null -> "NPU model loaded (${lastLoadMs.toInt()} ms), voice ${voice(context)}."
        else -> "NPU model downloaded."
    }
}
