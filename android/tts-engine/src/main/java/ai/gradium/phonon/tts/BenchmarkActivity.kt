package ai.gradium.phonon.tts

import ai.gradium.phonon.PhononTTS
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * CPU benchmark. Speed: load the selected checkpoint into a fresh native handle, then speak the
 * same text [runs] times; the first run is cold, warm is the mean of the rest. Battery: sample the
 * battery current while idle, then while generating non-stop, and report the difference.
 *
 * Only generation is measured; nothing is played. The test holds [Model.lifecycle], so the system
 * engine waits until it finishes.
 */
class BenchmarkActivity : ComponentActivity() {
    private var lines by mutableStateOf(listOf<String>())
    private var running by mutableStateOf(false)
    private var threads by mutableStateOf(2)
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        threads = Threads.configured(this)
        lines = listOf(deviceLine())
        setContent { BenchmarkScreen() }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    @Composable
    private fun BenchmarkScreen() {
        val dark = isSystemInDarkTheme()
        val context = LocalContext.current
        val colors = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
        var text by remember { mutableStateOf(DEFAULT_TEXT) }
        var runs by remember { mutableStateOf("5") }
        var seconds by remember { mutableStateOf("120") }
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("CPU benchmark", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Only generation is measured, nothing is played. For the battery test, unplug the " +
                            "phone, keep the screen on and leave it alone: idle current is measured first and " +
                            "subtracted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(text, { text = it }, label = { Text("Text (${text.length} characters)") },
                        enabled = !running, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(runs, { runs = it.filter(Char::isDigit).take(2) }, label = { Text("Runs") },
                            enabled = !running, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(4) },
                            label = { Text("Battery test (s)") }, enabled = !running, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("Threads (also used when reading aloud): " + (Threads.active?.let { "$it in use" } ?: "not loaded yet"),
                        style = MaterialTheme.typography.bodyMedium)
                    var pending by remember { mutableStateOf(threads) }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(value = pending.toFloat(), onValueChange = { pending = kotlin.math.round(it).toInt() },
                            valueRange = 1f..Threads.MAX.toFloat(), steps = (Threads.MAX - 2).coerceAtLeast(0),
                            enabled = !running, modifier = Modifier.weight(1f))
                        Text("$pending", style = MaterialTheme.typography.titleMedium)
                    }
                    val changes = pending != threads || (Threads.active != null && Threads.active != pending)
                    OutlinedButton(onClick = { pickThreads(pending) }, enabled = !running && changes,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (Threads.active == null) "Use $pending threads" else "Use $pending threads (restarts the app)")
                    }
                    Button(onClick = { start { speedTest(text, runs.toIntOrNull()?.coerceIn(2, 50) ?: 5) } },
                        enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run speed test") }
                    FilledTonalButton(onClick = { start { batteryTest(text, seconds.toIntOrNull()?.coerceIn(20, 1800) ?: 120) } },
                        enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run battery test") }
                    if (running) OutlinedButton(onClick = { cancelled = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    OutlinedButton(onClick = { copy() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Copy results") }
                    HorizontalDivider()
                    SelectionTextBlock(lines.joinToString("\n"))
                }
            }
        }
    }

    @Composable
    private fun SelectionTextBlock(value: String) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }

    /**
     * Save a thread count. The native pool only reads it at startup, so if one is already in use
     * this relaunches the benchmark in a fresh process.
     */
    private fun pickThreads(choice: Int) {
        if (running) return
        threads = choice
        Threads.save(this, choice)
        val active = Threads.active ?: return
        if (active == choice) return
        // Relaunch in a fresh process so the native pool starts with the new count.
        startActivity(Intent(this, BenchmarkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun start(task: () -> Unit) {
        if (running) return
        running = true
        cancelled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker = Thread {
            try { task() } catch (t: Throwable) { log("Failed: ${t.message ?: t.javaClass.simpleName}") }
            finally {
                runOnUiThread {
                    running = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.apply { name = "phonon-benchmark"; start() }
    }

    private fun log(line: String) = runOnUiThread { lines = lines + line }

    private class Handle(val tts: PhononTTS, val loadMs: Double, val label: String)

    /** Open the selected checkpoint in its own handle, timing the load. Caller holds [Model.lifecycle]. */
    private fun open(): Handle? {
        val entry = Model.selected(applicationContext) ?: run { log("No model installed."); return null }
        val lang = entry.languages.firstOrNull { it == "en" } ?: entry.languages.firstOrNull() ?: "none"
        // The same thread count the engine uses: the native pool reads it once per process.
        Threads.apply(applicationContext)
        BundledVoices.install(applicationContext, entry.directory, entry.languages)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val tts = PhononTTS(entry.directory.path, lang)
        val loadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val speaker = BundledVoices.selectedMetadata(applicationContext, entry, lang)
        if (speaker in tts.voices) tts.setVoice(speaker)
        return Handle(tts, loadMs, "${entry.id} · $lang · ${BundledVoices.label(speaker)}")
    }

    private fun speedTest(text: String, runs: Int) {
        log("")
        log("== Speed test · ${text.length} chars · $runs runs · threads=${Threads.active ?: Threads.configured(applicationContext)}")
        log("Thermal at start: ${thermal()}")
        synchronized(Model.lifecycle) {
            val handle = open() ?: return
            try {
                log("Model: ${handle.label}")
                log("Load time: ${fmt(handle.loadMs / 1000, 2)} s")
                val results = mutableListOf<PhononTTS.Result>()
                for (i in 0 until runs) {
                    if (cancelled) { log("Cancelled."); return }
                    val r = handle.tts.speak(text) { !cancelled }
                    results += r
                    log("Run ${i + 1}: TTFA ${fmt(r.ttfaMs, 0)} ms · ${fmt(rtfx(r), 2)}x · ${fmt(audioSeconds(r), 1)} s audio")
                }
                val cold = results.first()
                val warm = results.drop(1)
                log("TTFA (cold): ${fmt(cold.ttfaMs, 0)} ms")
                log("RTFx (cold): ${fmt(rtfx(cold), 2)}x")
                log("TTFA (warm): ${fmt(warm.map { it.ttfaMs }.average(), 0)} ms")
                log("RTFx (warm): ${fmt(warm.sumOf { audioSeconds(it) } / warm.sumOf { it.totalMs / 1000 }, 2)}x")
            } finally { handle.tts.close() }
        }
        log("Thermal at end: ${thermal()}")
    }

    private fun batteryTest(text: String, seconds: Int) {
        log("")
        log("== Battery test · ${seconds}s idle + ${seconds}s generating")
        if (charging()) log("WARNING: the phone is charging; current readings are meaningless. Unplug it.")
        log("Thermal at start: ${thermal()}")
        val idle = sample(seconds) { !cancelled }
        if (cancelled) { log("Cancelled."); return }
        log("Idle: ${fmt(idle.meanMa, 0)} mA (${idle.samples} samples)")
        synchronized(Model.lifecycle) {
            val handle = open() ?: return
            try {
                log("Model: ${handle.label}")
                var audio = 0.0
                var busyMs = 0.0
                val load = sample(seconds, background = {
                    val r = handle.tts.speak(text) { !cancelled }
                    audio += audioSeconds(r)
                    busyMs += r.totalMs
                }) { !cancelled }
                if (cancelled) { log("Cancelled."); return }
                val extra = load.meanMa - idle.meanMa
                val speed = if (busyMs > 0) audio / (busyMs / 1000) else 0.0
                log("Generating: ${fmt(load.meanMa, 0)} mA (${load.samples} samples), ${fmt(speed, 2)}x real time")
                log("Battery usage (generation only): ${fmt(extra, 0)} mAh / hour of non-stop generation")
                if (load.counterMah != null) log("Charge counter drop while generating: ${fmt(load.counterMah, 1)} mAh")
                if (speed > 0) {
                    val listening = extra / speed
                    val capacity = capacityMah()
                    val share = capacity?.let { " (≈${fmt(100 * listening / it, 2)}% of a ${fmt(it, 0)} mAh battery)" } ?: ""
                    log("Estimated listening cost: ${fmt(listening, 0)} mAh per hour of audio$share")
                    log("  = extra current ÷ speed; ignores wake-ups and playback itself.")
                }
                log("Current unit read as: ${load.unit}")
            } finally { handle.tts.close() }
        }
        log("Thermal at end: ${thermal()}")
    }

    private class Reading(val meanMa: Double, val samples: Int, val counterMah: Double?, val unit: String)

    /**
     * Sample the battery current every 250 ms for [seconds]. With [background], run it in a loop
     * on another thread meanwhile. BATTERY_PROPERTY_CURRENT_NOW is specified in µA, but some
     * devices report mA; values that are implausibly small for µA are read as mA.
     */
    private fun sample(seconds: Int, background: (() -> Unit)? = null, keepGoing: () -> Boolean): Reading {
        val battery = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val counterStart = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val load = background?.let { work ->
            Thread {
                try { while (!done.get() && keepGoing()) work() } catch (t: Throwable) { failure.set(t) }
            }.apply { start() }
        }
        val raw = mutableListOf<Long>()
        try {
            while (SystemClock.elapsedRealtime() < end && keepGoing() && failure.get() == null) {
                val value = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (value != Long.MIN_VALUE && value != 0L) raw += abs(value)
                Thread.sleep(250)
            }
        } finally {
            done.set(true)
            load?.join()
        }
        failure.get()?.let { throw it }
        val counterEnd = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val median = raw.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val milliamps = median in 1 until 20_000
        val mean = if (raw.isEmpty()) 0.0 else raw.average() / (if (milliamps) 1.0 else 1000.0)
        val counter = if (counterStart > 0 && counterEnd > 0) (counterStart - counterEnd) / 1000.0 else null
        return Reading(mean, raw.size, counter, if (milliamps) "mA (device-specific)" else "µA (Android standard)")
    }

    private fun capacityMah(): Double? {
        val battery = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val counter = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (counter <= 0 || level <= 0) return null
        return counter / 1000.0 / (level / 100.0)
    }

    private fun charging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun thermal(): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown"
        return when ((getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun deviceLine(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL}$soc · Android ${Build.VERSION.RELEASE} · " +
            "${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Phonon benchmark", lines.joinToString("\n")))
    }

    private companion object {
        const val SAMPLE_RATE = 24000.0

        /** About 160 characters: a typical webnovel paragraph of narration and dialogue. */
        const val DEFAULT_TEXT = "The old swordsman lowered his blade and smiled. \"You have grown strong, Lin Feng. " +
            "But strength alone will not save you from what waits beyond the northern gate.\""

        fun audioSeconds(r: PhononTTS.Result) = r.samples / SAMPLE_RATE
        fun rtfx(r: PhononTTS.Result) = if (r.totalMs > 0) audioSeconds(r) / (r.totalMs / 1000) else 0.0
        fun fmt(value: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", value)
    }
}
