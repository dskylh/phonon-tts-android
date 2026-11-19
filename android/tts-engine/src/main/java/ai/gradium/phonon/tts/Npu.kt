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
import javax.net.ssl.HttpsURLConnection
import ai.gradium.phonon.PhononTTS as NpuTTS

/**
 * Phonon on the Snapdragon NPU (Qualcomm HTP), for the one phone this bundle was compiled for:
 * SoC SM8550 (Galaxy S23). English only, one voice. Everywhere else, and whenever loading fails,
 * speech stays on the CPU engine.
 *
 * Every native NPU call holds [lock]: the engine allows one speak at a time and no close during one.
 */
object Npu {
    private const val TAG = "PhononNpu"
    const val SOC = "SM8550"
    private const val BUNDLE = "npu-s23-v1"
    // Pinned: a new bundle gets a new release tag, URL and hash.
    private const val URL_ZIP =
        "https://github.com/dskylh/phonon-tts-android/releases/download/npu-bundle-s23-v1/phonon-npu-s23-bundle.zip"
    private const val ZIP_SIZE = 69_535_222L
    private const val ZIP_SHA256 = "4c6c2fe6b2a6fa209cf597d26218bd5e91bb23e379737a3e1e32daf1a6800b86"
    private const val UNPACKED_LIMIT = 200L * 1024 * 1024
    private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val REQUIRED = listOf("metadata.json", "phonon-sm8550.bin", "tokenizer.json", "bos.bin", "voices/default.bin")

    val lock = Any()
    @Volatile private var engine: NpuTTS? = null
    /** Why the NPU is unusable in this process, after a load or speak failure. */
    @Volatile var failure: String? = null; private set
    @Volatile var lastLoadMs: Double = 0.0; private set

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.uppercase(Locale.ROOT) == SOC
    fun dir(context: Context) = File(context.filesDir, "phonon/$BUNDLE")
    fun installed(context: Context) = REQUIRED.all { File(dir(context), it).isFile }

    private fun prefs(context: Context) = context.getSharedPreferences("npu", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        if (!on) release()
    }

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
            if ("default" in tts.voices) tts.setVoice("default")
            Log.i(TAG, "NPU engine loaded in ${lastLoadMs.toInt()} ms, voices ${tts.voices}")
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
        try { work(tts) } catch (t: Throwable) {
            fail("speak", t)
            throw t
        }
    }

    private fun fail(stage: String, t: Throwable) {
        Log.e(TAG, "NPU $stage failed; using the CPU engine", t)
        failure = "$stage: ${t.javaClass.simpleName}: ${t.message}"
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
    }

    /** Close the engine and allow a fresh load (also clears a previous failure). */
    fun release() = synchronized(lock) {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        failure = null
    }

    fun delete(context: Context) = synchronized(lock) {
        release()
        dir(context).deleteRecursively()
    }

    /** Download the pinned bundle, check its size and SHA-256, and unpack it. Blocks; call off the main thread. */
    fun download(context: Context, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val root = File(context.filesDir, "phonon").apply { mkdirs() }
        require(root.usableSpace > ZIP_SIZE + UNPACKED_LIMIT) { "Not enough storage (needs about 170 MB free)" }
        val zip = File(root, "$BUNDLE.zip.part")
        val staging = File(root, "$BUNDLE.staging")
        try {
            fetch(zip, cancelled, progress)
            if (cancelled()) throw CancellationException()
            unpack(zip, staging, cancelled)
            check(REQUIRED.all { File(staging, it).isFile }) { "Bundle is missing files" }
            synchronized(lock) {
                release()
                val target = dir(context)
                target.deleteRecursively()
                check(staging.renameTo(target)) { "Cannot install the bundle" }
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
        !installed(context) -> "NPU model not downloaded (69.5 MB)."
        failure != null -> "NPU failed, using CPU: $failure"
        engine != null -> "NPU model loaded (${lastLoadMs.toInt()} ms)."
        else -> "NPU model downloaded."
    }
}
