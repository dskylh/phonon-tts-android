package ai.gradium.phonon.tts

import android.content.Context
import android.system.Os

/**
 * How many CPU threads synthesis uses, for the engine and the benchmark alike. The native thread
 * pool reads RAYON_NUM_THREADS once per process, so a new choice takes effect after a restart.
 */
object Threads {
    /** 1 up to the number of cores, at most 8. */
    val MAX = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private const val DEFAULT = 2
    @Volatile var active: Int? = null; private set

    private fun prefs(context: Context) = context.getSharedPreferences("benchmark", Context.MODE_PRIVATE)

    fun configured(context: Context): Int = prefs(context).getInt("threads", DEFAULT).coerceIn(1, MAX)

    fun save(context: Context, threads: Int) { prefs(context).edit().putInt("threads", threads).commit() }

    /** Call before the first native call in this process. Returns the count in effect. */
    @Synchronized fun apply(context: Context): Int {
        active?.let { return it }
        val threads = configured(context)
        Os.setenv("RAYON_NUM_THREADS", threads.toString(), true)
        active = threads
        return threads
    }
}
