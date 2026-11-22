# JNA finds the library's functions and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# Kokoro NPU and Misaki JNI bridges.
-keep class ai.gradium.phonon.kokoro.KokoroNative { *; }
-keep interface ai.gradium.phonon.kokoro.KokoroAudioCallback { *; }
-keep class com.local.kokorotts.NativeMisaki { *; }
