package ai.gradium.phonon.tts

import ai.gradium.phonon.PhononTTS as NpuTTS
import ai.gradium.phonon.cpu.PhononTTS
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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var onNpu by mutableStateOf(false)
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        threads = Threads.configured(this)
        onNpu = Npu.enabled(this) && Npu.installed(this)
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
        var chapter by remember { mutableStateOf("") }
        var minutes by remember { mutableStateOf("10") }
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Benchmark", style = MaterialTheme.typography.headlineSmall)
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
                    val npuReady = Npu.supported() && Npu.installed(this@BenchmarkActivity)
                    Text("Engine to test", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!onNpu) Button(onClick = {}, enabled = !running) { Text("CPU") }
                        else OutlinedButton(onClick = { onNpu = false }, enabled = !running) { Text("CPU") }
                        if (onNpu) Button(onClick = {}, enabled = !running) { Text("NPU") }
                        else OutlinedButton(onClick = { onNpu = true }, enabled = !running && npuReady) { Text("NPU") }
                    }
                    if (!npuReady) Text(if (Npu.supported()) "Download the NPU model in settings to test the NPU."
                        else "The NPU model only runs on ${Npu.SOC} (Galaxy S23).", style = MaterialTheme.typography.bodySmall)
                    if (!onNpu) Text("CPU threads (also used when reading aloud): " + (Threads.active?.let { "$it in use" } ?: "not loaded yet"),
                        style = MaterialTheme.typography.bodyMedium)
                    var pending by remember { mutableStateOf(threads) }
                    if (!onNpu) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(value = pending.toFloat(), onValueChange = { pending = kotlin.math.round(it).toInt() },
                            valueRange = 1f..Threads.MAX.toFloat(), steps = (Threads.MAX - 2).coerceAtLeast(0),
                            enabled = !running, modifier = Modifier.weight(1f))
                        Text("$pending", style = MaterialTheme.typography.titleMedium)
                    }
                    val changes = pending != threads || (Threads.active != null && Threads.active != pending)
                    if (!onNpu) OutlinedButton(onClick = { pickThreads(pending) }, enabled = !running && changes,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (Threads.active == null) "Use $pending threads" else "Use $pending threads (restarts the app)")
                    }
                    Button(onClick = { val npu = onNpu; start { speedTest(npu, text, runs.toIntOrNull()?.coerceIn(2, 50) ?: 5) } },
                        enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run speed test") }
                    FilledTonalButton(onClick = { val npu = onNpu; start { batteryTest(npu, text, seconds.toIntOrNull()?.coerceIn(20, 1800) ?: 120) } },
                        enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run battery test") }
                    HorizontalDivider()
                    Text("Listening test", style = MaterialTheme.typography.titleMedium)
                    Text("Reads the chapter aloud through the system speech service, one paragraph at a time like " +
                        "Reikai. Three phases of the same length: idle (nothing playing), CPU, then NPU. Unplug the " +
                        "phone, set the volume low and keep the screen the way you read (on or off) for the whole " +
                        "test; it records how long the screen was on. Total time is 3 × the minutes below.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(chapter, { chapter = it }, label = { Text("Chapter text (${chapter.length} characters, " +
                        "${chapter.lines().count { it.isNotBlank() }} paragraphs)") },
                        enabled = !running, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes per phase") }, enabled = !running, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { val c = chapter; start { listenTest(c, minutes.toIntOrNull()?.coerceIn(2, 30) ?: 10) } },
                        enabled = !running && chapter.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Run listening test (idle, CPU, NPU)")
                    }
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

    private class Run(val samples: Long, val ttfaMs: Double, val totalMs: Double)
    private class Handle(val label: String, val loadMs: Double, val speak: (String) -> Run, val close: () -> Unit)

    /** Open a fresh engine (CPU or NPU), timing the load, run [work] with it, then close it. */
    private fun <T> withEngine(npu: Boolean, work: (Handle) -> T): T? =
        if (npu) synchronized(Npu.lock) { openNpu()?.let { h -> try { work(h) } finally { h.close() } } }
        else synchronized(Model.lifecycle) { openCpu()?.let { h -> try { work(h) } finally { h.close() } } }

    /** The selected CPU checkpoint in its own handle. Caller holds [Model.lifecycle]. */
    private fun openCpu(): Handle? {
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
        return Handle("CPU · ${entry.id} · $lang · ${BundledVoices.label(speaker)} · threads=${Threads.active}", loadMs,
            { text -> tts.speak(text) { !cancelled }.let { Run(it.samples, it.ttfaMs, it.totalMs) } }, { tts.close() })
    }

    /** The NPU bundle in a fresh engine (the system engine's copy is closed first). Caller holds [Npu.lock]. */
    private fun openNpu(): Handle? {
        if (!Npu.supported() || !Npu.installed(applicationContext)) { log("NPU model not available on this phone."); return null }
        Npu.release()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val tts = try { NpuTTS.load(applicationContext, Npu.dir(applicationContext), "en") } catch (t: Throwable) {
            log("NPU load failed: $t")
            var cause = t.cause
            while (cause != null) { log("  caused by: $cause"); cause = cause.cause }
            log("  See logcat (tags PhononNpu, QNN, ptts) for details.")
            return null
        }
        val loadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val voice = Npu.voice(applicationContext)
        if (voice in tts.voices) tts.setVoice(voice)
        return Handle("NPU · S23 bundle · en · $voice · ${tts.voices.size} voices · ${tts.sampleRate} Hz", loadMs,
            { text -> tts.speak(text) { !cancelled }.let { Run(it.samples, it.ttfaMs, it.totalMs) } }, { tts.close() })
    }

    private fun speedTest(npu: Boolean, text: String, runs: Int) {
        log("")
        log("== Speed test · ${if (npu) "NPU" else "CPU"} · ${text.length} chars · $runs runs")
        log("Thermal at start: ${thermal()}")
        withEngine(npu) { handle ->
            log("Model: ${handle.label}")
            log("Load time: ${fmt(handle.loadMs / 1000, 2)} s")
            val results = mutableListOf<Run>()
            for (i in 0 until runs) {
                if (cancelled) { log("Cancelled."); return@withEngine }
                val r = handle.speak(text)
                results += r
                log("Run ${i + 1}: TTFA ${fmt(r.ttfaMs, 0)} ms · ${fmt(rtfx(r), 2)}x · ${fmt(audioSeconds(r), 1)} s audio")
            }
            val cold = results.first()
            val warm = results.drop(1)
            log("TTFA (cold): ${fmt(cold.ttfaMs, 0)} ms")
            log("RTFx (cold): ${fmt(rtfx(cold), 2)}x")
            log("TTFA (warm): ${fmt(warm.map { it.ttfaMs }.average(), 0)} ms")
            log("RTFx (warm): ${fmt(warm.sumOf { audioSeconds(it) } / warm.sumOf { it.totalMs / 1000 }, 2)}x")
        }
        log("Thermal at end: ${thermal()}")
    }

    private fun batteryTest(npu: Boolean, text: String, seconds: Int) {
        log("")
        log("== Battery test · ${if (npu) "NPU" else "CPU threads=${Threads.active ?: Threads.configured(applicationContext)}"} · ${seconds}s idle + ${seconds}s generating")
        if (charging()) log("WARNING: the phone is charging; current readings are meaningless. Unplug it.")
        log("Thermal at start: ${thermal()}")
        val idle = sample(seconds) { !cancelled }
        if (cancelled) { log("Cancelled."); return }
        log("Idle: ${fmt(idle.meanMa, 0)} mA (${idle.samples} samples)")
        withEngine(npu) { handle ->
            log("Model: ${handle.label}")
            var audio = 0.0
            var busyMs = 0.0
            val load = sample(seconds, background = {
                val r = handle.speak(text)
                audio += audioSeconds(r)
                busyMs += r.totalMs
            }) { !cancelled }
            if (cancelled) { log("Cancelled."); return@withEngine }
            val extra = load.meanMa - idle.meanMa
            val speed = if (busyMs > 0) audio / (busyMs / 1000) else 0.0
            val capacity = capacityMah()
            fun share(mah: Double) = capacity?.let { " (≈${fmt(100 * mah / it, 2)}% of a ${fmt(it, 0)} mAh battery)" } ?: ""
            log("Generating: ${fmt(load.meanMa, 0)} mA (${load.samples} samples), ${fmt(speed, 2)}x real time")
            log("Battery usage (generation only): ${fmt(extra, 0)} mAh / hour of non-stop generation")
            if (speed > 0) {
                log("Estimated listening cost: ${fmt(extra / speed, 0)} mAh per hour of audio${share(extra / speed)}")
                log("  = extra current ÷ speed; ignores wake-ups and playback itself.")
            }
            // Cross-check with the battery's own charge counter over the same two windows.
            val idleCounter = idle.counterMah?.let { it * 3600 / seconds }
            val loadCounter = load.counterMah?.let { it * 3600 / seconds }
            if (load.counterMah != null) log("Charge counter drop while generating: ${fmt(load.counterMah, 1)} mAh (≈${fmt(loadCounter!!, 0)} mA)")
            if (idleCounter != null && loadCounter != null && speed > 0) {
                val counterExtra = loadCounter - idleCounter
                log("By charge counter: idle ≈${fmt(idleCounter, 0)} mA, extra ≈${fmt(counterExtra, 0)} mA, " +
                    "listening ≈${fmt(counterExtra / speed, 0)} mAh/h${share(counterExtra / speed)}")
                if (load.meanMa > 0 && kotlin.math.abs(loadCounter - load.meanMa) > 0.25 * load.meanMa)
                    log("WARNING: current readings and charge counter disagree by more than 25%; repeat the test.")
            }
            log("Current unit read as: ${load.unit}")
        }
        log("Thermal at end: ${thermal()}")
    }

    /**
     * Read [chapter] aloud through Android's TextToSpeech with this app as the engine, one paragraph
     * per speak() and waiting for each to finish, as Reikai does. Phases of [minutes] each: idle
     * (CPU awake, nothing playing), CPU engine, NPU engine; the screen is left as the user sets it. Same text from the start in
     * each phase. A partial wake lock is held throughout, since playback keeps the CPU awake anyway.
     */
    private fun listenTest(chapter: String, minutes: Int) {
        val paragraphs = chapter.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = paragraphs.sumOf { SentenceSpans.split(it, Locale.ENGLISH).size }
        val seconds = minutes * 60
        val npuReady = Npu.supported() && Npu.installed(applicationContext)
        log("")
        log("== Listening test · ${paragraphs.size} paragraphs · $sentences sentences · " +
            "${fmt(chapter.length.toDouble() / sentences.coerceAtLeast(1), 0)} chars per sentence · " +
            "${fmt(chapter.length.toDouble() / paragraphs.size, 0)} per paragraph · $minutes min per phase")
        if (charging()) log("WARNING: the phone is charging; current readings are meaningless. Unplug it.")
        if (!npuReady) log("NPU model not available: the NPU phase is skipped.")
        val npuWas = Npu.enabled(applicationContext)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonon:listen-test")
        // The paragraph being spoken and its latch; callbacks for any other utterance are ignored.
        val finished = java.util.concurrent.atomic.AtomicReference<Pair<String, java.util.concurrent.CountDownLatch>?>(null)
        fun end(id: String?) { finished.get()?.takeIf { it.first == id }?.second?.countDown() }
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val ready = java.util.concurrent.CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        val tts = TextToSpeech(applicationContext, { initStatus = it; ready.countDown() }, packageName)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = end(id)
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (finished.get()?.first == id) failed.set(true); end(id) }
            override fun onError(id: String?, code: Int) { if (finished.get()?.first == id) failed.set(true); end(id) }
            override fun onStop(id: String?, interrupted: Boolean) = end(id)
        })
        ListenTestService.start(applicationContext)
        lock.acquire(4 * 60 * 60 * 1000L)
        runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            if (!ready.await(30, java.util.concurrent.TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
                log("Could not start the speech service."); return
            }
            tts.language = Locale.US
            log("Starting in 10 s. Set the screen the way you read (on or off) and keep it that way for the whole test.")
            for (i in 0 until 10) { if (cancelled) { log("Cancelled."); return }; Thread.sleep(1000) }
            log("Thermal at start: ${thermal()}")

            fun screenNote(r: Reading) = " · screen on ${100 * r.screenOn / seconds}% of the time"
            val idle = sample(seconds, intervalMs = 1000) { !cancelled }
            if (cancelled) { log("Cancelled."); return }
            log("Idle (nothing playing): ${fmt(idle.meanMa, 0)} mA (${idle.samples} samples)${screenNote(idle)}")

            class Phase(val name: String, val reading: Reading, val paragraphs: Int, val npu: Long, val cpu: Long)
            fun listen(npu: Boolean): Phase? {
                Npu.setEnabled(applicationContext, npu)
                var next = 0
                var spoken = 0
                val npu0 = SpeechStats.npu.get(); val cpu0 = SpeechStats.cpu.get()
                failed.set(false)
                val reading = sample(seconds, intervalMs = 1000, onEnd = { tts.stop(); finished.get()?.second?.countDown() }, background = work@{
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val id = "${if (npu) "npu" else "cpu"}-$next"
                    finished.set(id to latch)
                    tts.speak(paragraphs[next % paragraphs.size], TextToSpeech.QUEUE_ADD, null, id)
                    next++
                    while (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) if (cancelled) { tts.stop(); return@work }
                    if (!failed.get()) spoken++
                }) { !cancelled }
                if (cancelled) { log("Cancelled."); return null }
                val name = if (npu) "NPU (${Npu.voice(applicationContext)})" else "CPU (threads=${Threads.active ?: Threads.configured(applicationContext)})"
                val phase = Phase(name, reading, spoken, SpeechStats.npu.get() - npu0, SpeechStats.cpu.get() - cpu0)
                log("$name: ${fmt(reading.meanMa, 0)} mA (${reading.samples} samples) · ${phase.paragraphs} paragraphs · " +
                    "sentences on NPU ${phase.npu}, on CPU ${phase.cpu}${if (failed.get()) " · some paragraphs failed" else ""}${screenNote(reading)}")
                log("Thermal: ${thermal()}")
                return phase
            }
            val phases = listOfNotNull(listen(false) ?: return, if (npuReady) (listen(true) ?: return) else null)

            val capacity = capacityMah()
            fun share(mah: Double) = capacity?.let { " (${fmt(100 * mah / it, 2)}%)" } ?: ""
            log("")
            val screens = (listOf(idle) + phases.map { it.reading }).map { 100 * it.screenOn / seconds }
            if (screens.max() - screens.min() > 10)
                log("WARNING: the screen was on for different shares of each phase (${screens.joinToString("/")}%); the comparison is skewed.")
            log("Per hour of listening (everything included, screen as during the test):")
            for (p in phases) log("  ${p.name}: ${fmt(p.reading.meanMa, 0)} mAh${share(p.reading.meanMa)} · " +
                "extra over idle ${fmt(p.reading.meanMa - idle.meanMa, 0)} mAh${share(p.reading.meanMa - idle.meanMa)}")
            if (phases.size == 2) {
                val (cpu, npu) = phases
                val saved = cpu.reading.meanMa - npu.reading.meanMa
                log("  NPU vs CPU: ${if (saved >= 0) "saves" else "costs"} ${fmt(abs(saved), 0)} mAh per hour${share(abs(saved))}")
                if (npu.npu == 0L) log("WARNING: nothing ran on the NPU in the NPU phase; check the NPU status in settings.")
            }
            capacity?.let { log("Battery capacity estimate: ${fmt(it, 0)} mAh") }
            log("Percentages use that estimate. Hours of listening on a full battery = capacity ÷ mAh per hour.")
        } finally {
            Npu.setEnabled(applicationContext, npuWas)
            tts.stop(); tts.shutdown()
            if (lock.isHeld) lock.release()
            ListenTestService.stop(applicationContext)
        }
    }

    /** [screenOn]: seconds the screen was on during the sampling (only checked by the listening test). */
    private class Reading(val meanMa: Double, val samples: Int, val counterMah: Double?, val unit: String, val screenOn: Int = 0)

    /**
     * Sample the battery current every 250 ms for [seconds]. With [background], run it in a loop
     * on another thread meanwhile. BATTERY_PROPERTY_CURRENT_NOW is specified in µA, but some
     * devices report mA; values that are implausibly small for µA are read as mA.
     */
    private fun sample(seconds: Int, background: (() -> Unit)? = null, intervalMs: Long = 250,
        onEnd: (() -> Unit)? = null, keepGoing: () -> Boolean): Reading {
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
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        var screenOnMs = 0L
        try {
            while (SystemClock.elapsedRealtime() < end && keepGoing() && failure.get() == null) {
                val value = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (value != Long.MIN_VALUE && value != 0L) raw += abs(value)
                if (power.isInteractive) screenOnMs += intervalMs
                Thread.sleep(intervalMs)
            }
        } finally {
            done.set(true)
            onEnd?.invoke()
            load?.join()
        }
        failure.get()?.let { throw it }
        val counterEnd = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val median = raw.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val milliamps = median in 1 until 20_000
        val mean = if (raw.isEmpty()) 0.0 else raw.average() / (if (milliamps) 1.0 else 1000.0)
        val counter = if (counterStart > 0 && counterEnd > 0) (counterStart - counterEnd) / 1000.0 else null
        return Reading(mean, raw.size, counter, if (milliamps) "mA (device-specific)" else "µA (Android standard)", (screenOnMs / 1000).toInt())
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
        val status = if (Build.VERSION.SDK_INT < 29) "unknown" else when ((getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
        val temperature = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?.let { " · battery ${fmt(it / 10.0, 1)} °C" } ?: ""
        // 0 = cool, 1 = about to throttle; NaN where the device does not report it.
        val headroom = if (Build.VERSION.SDK_INT >= 30)
            (getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10).takeIf { !it.isNaN() }
                ?.let { " · headroom ${fmt(it.toDouble(), 2)}" } ?: "" else ""
        return "throttling $status$temperature$headroom"
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

        fun audioSeconds(r: Run) = r.samples / SAMPLE_RATE
        fun rtfx(r: Run) = if (r.totalMs > 0) audioSeconds(r) / (r.totalMs / 1000) else 0.0
        fun fmt(value: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", value)
    }
}
