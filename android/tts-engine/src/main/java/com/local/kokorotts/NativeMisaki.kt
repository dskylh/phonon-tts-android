package com.local.kokorotts

/**
 * JNI bridge to libmisaki_android.so (Apache-2.0 Misaki G2P with optional eSpeak NG OOV fallback).
 * Package and class name match the exported JNI symbols in libmisaki_android.so.
 */
internal object NativeMisaki {
    init {
        System.loadLibrary("misaki_android")
    }

    external fun initialize(libraryPath: String, dataPath: String): String?
    external fun phonemize(text: String, british: Boolean): String
}
