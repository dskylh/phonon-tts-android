# ptts-release.aar (Phonon on the Snapdragon NPU)

`ptts-release.aar`, sha256 `9b3fff7184041fe36796e1baa83b10464b3ec5967ad6fe0213550c2b7452b9c5`.

## Build

Built from [gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts) branch `feat/phonon-qnn`, commit
`249c6ec327fcaab403a970f73f4e5c4df5ffb2fb` (workspace version 0.4.0). The working tree carried the patch
at the end of this file; it touches only `qnn/export/*.py` (the model export), nothing the AAR is built from.

```sh
cd qnn/android
export QAIRT_ROOT=/path/to/qairt/2.50.0.260828 ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk-21
./gradlew :ptts:assembleRelease     # -> ptts/build/outputs/aar/ptts-release.aar
```

JDK 21.0.2 (Gradle 8.13 does not run on JDK 26), AGP 8.13.0, Kotlin 2.2.20, compileSdk 36, minSdk 31,
NDK 29.0.14206865, CMake 3.31.6, QAIRT 2.50.0.260828, arm64-v8a only.

## Contents

```
Archive:  ptts-release.aar
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  02-01-1980 00:00   R.txt
      347  02-01-1980 00:00   AndroidManifest.xml
    11932  02-01-1980 00:00   classes.jar
      327  02-01-1980 00:00   proguard.txt
      156  02-01-1980 00:00   META-INF/com/android/build/gradle/aar-metadata.properties
        0  02-01-1980 00:00   jni/
        0  02-01-1980 00:00   jni/arm64-v8a/
  1106728  02-01-1980 00:00   jni/arm64-v8a/libptts_qnn.so
  3778720  02-01-1980 00:00   jni/arm64-v8a/libptts_text.so
---------                     -------
  4898210                     9 files
```

| library | where it comes from |
|---|---|
| libptts_qnn.so | this AAR (JNI and the QNN runner) |
| libptts_text.so | this AAR (text normalization, chunking, tokenizer) |
| libQnnHtp.so, libQnnSystem.so, libQnnHtpV73Stub.so, libQnnHtpV73Skel.so | **not in the AAR**: Maven `com.qualcomm.qti:qnn-runtime:2.50.0` |

`com.qualcomm.qti:qnn-runtime:2.50.0` (71 MB AAR on Maven Central) has, under `jni/arm64-v8a/`:
libQnnHtp.so, libQnnSystem.so, libQnnHtpPrepare.so, libQnnGpu.so, libQnnDsp.so, and Stub/Skel pairs for
HTP V68, V69, **V73**, V75, V79, V81 (libQnnHtpV73Stub.so 791,696 bytes, libQnnHtpV73Skel.so 18,709,712
bytes). Its libraries report build `2.50.0.260828221209`, the same build as the SDK used here and as the
context binary (`buildId v2.50.0.260828221209`); the files are not byte-identical to the SDK's copies.

A local `.aar` does not bring its POM dependencies, so the app must declare both:

```kotlin
dependencies {
    implementation(files("libs/ptts-release.aar"))
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
}
android { packaging { jniLibs { useLegacyPackaging = true } } }   // PhononTTS.load checks libQnnHtp.so is extracted
```

The AAR manifest declares `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`.
The JNI loader prepends the app's native library directory to `ADSP_LIBRARY_PATH`, which is where the
skel is loaded from.

## Model bundle (not in this repo)

The working S23 bundle is kept off-repo (`phonon-npu-s23-bundle.zip`, 89 MB unpacked), sha256
`fe901525a16f863f2fcbb46f1aea5035d2d9f6b96d00cc29d25792eaed9ea093`. Unzip it into a directory and pass
that directory to `PhononTTS.load`.

```
Archive:  phonon-npu-s23-bundle.zip
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  11-19-2026 21:50   voices/
  1536000  11-19-2026 21:50   voices/default.bin
        0  11-19-2026 21:50   lib/
        0  11-19-2026 21:50   lib/android-arm64/
  3778728  11-19-2026 21:50   lib/android-arm64/libptts_text.so
        0  11-19-2026 21:50   lib/linux-arm64/
 83619840  11-19-2026 21:50   phonon-sm8550.bin
   277967  11-19-2026 21:50   tokenizer.json
     1024  11-19-2026 21:50   bos.bin
     5101  11-19-2026 21:50   metadata.json
---------                     -------
 89218660                     10 files
```

`lib/android-arm64/libptts_text.so` in the bundle is not used by the AAR (the AAR loads its own copy).
`metadata.json` lists `"soc_models": ["SM8550"]`, which `PhononTTS.load` matches against
`Build.SOC_MODEL`.

## Facts measured on the phone

Galaxy S23 SM-S911B, Android 17. `ro.soc.model` = `SM8550` (`ro.soc.manufacturer` QTI), which is what
`Build.SOC_MODEL` returns.

Everything below came from the command-line runner (`qnn/runner`, `run-android.sh`) with the QAIRT
2.50.0.260828 SDK libraries pushed to `/data/local/tmp`. **The AAR has not been run in an app yet**
(neither the smoke app nor this one), so `useLegacyPackaging`, `libcdsprpc.so` access and skel loading
from the APK are untested. The runner had no problems with the skel or `ADSP_LIBRARY_PATH`.

**Speed** (`PHONON_QNN_PROFILE=1`, the 12-word sentence "Hello! This is Phonon, running on the Galaxy S23 neural processing unit.", 71 frames, 5.68 s of audio):

```
prefill 2.5 ms, step 4.30 ms/frame, first audio after 11.7 ms, total 313.5 ms (18.1x real time)
on the NPU: prefill 2.15 ms/call, step 4.07 ms/call (72 calls)
```

Three sentences, two chunks, 207 frames, 16.56 s of audio: 18.3-18.5x real time, step 4.26-4.31 ms/frame,
first audio 12.7-14.5 ms. The CPU model on the same phone runs at about 7.2x real time (~11 ms/frame).

**Load and memory**: process wall time 0.93 s for load plus that 0.31 s synthesis, so load (context
binary deserialization, graph setup) is about 0.6 s. Max RSS of the runner process 97.5 MiB. AI Hub
profile peak memory: prefill 101 MiB, step 105 MiB.

**Performance mode**: `Phonon::synthesize` always sets HTP burst for the duration of a `speak` call
(`qnn_model.cpp` `set_performance_mode`: DCVS v3, DCVS disabled, PERFORMANCE_MODE, bus and core voltage
corners at MAX, sleep disabled, RPC polling 9999 us) and resets it afterwards. The Kotlin API has no
option for this. With a one-line local change that skips the burst config (left to the system's DCVS;
not in this AAR), the same three sentences ran at 5.7x real time: step 13.8 ms/frame (NPU 11.4 ms/call),
prefill 7.9-8.8 ms, first audio 25-26 ms. A lower-power mode would need an API addition.

**Streaming**: `speak(text) { pcm -> ... }` blocks and calls back once per 80 ms frame with a
FloatArray of 1920 mono samples at 24 kHz (`sampleRate` = 24000). Text goes through ptts_text's
normalization and is split into chunks of at most 50 tokens (`max_tokens_per_chunk`, prefill holds 64);
each chunk is one prefill followed by steps until EOS plus 2 frames (4 for texts of up to 4 words), capped
at `ceil((tokens / 3 + 2) * 12.5)` frames. Return false from the callback or call `stop()` to cancel.

**Voices**: the bundle has one voice, `default` (the checkpoint's `default-voice.safetensors`). A voice
file is raw fp16 `[2 * layers, heads, n, head_dim]` = `[12, 8, 125, 64]`, 1,536,000 bytes: the voice
prompt's keys and values, computed on the host by running the backbone in fp32 (`host.voice_cache`).
CPU-model voices (`.safetensors` with `emb` or `speaker_wavs`) convert without an NPU export or
compile: put them in `PHONON_VOICES` and rerun `qnn/export/bundle.py`, which writes one `.bin` per
voice and lists it in `metadata.json`.

**Quality**: AI Hub per-call check on a real S23 (PC fp16 inputs replayed through the context binary,
prefill x1, step x24): prefill kv_new rel. error 6.3e-3; step kv_new 7.6e-3, latent 9.5e-3, EOS logits
within 0.07 of the PC; audio output 5.6e-2 median. Listened to on the phone: correct speech, stops at EOS
(5.68 s vs 5.52 s for the PC run of the same sentence). No SNR figure.

## What broke on HTP and was fixed

The `feat/phonon-qnn` prefill gave garbled audio on the NPU: right voice, unrelated babble, no EOS.
Prefill's one-hot token lookup was `(tokens == vocab).float()`, a bool to fp16 Cast. On the HTP that Cast
is computed correctly, but a LayerNorm downstream of it is not, so every text token reached attention as
noise (prefill kv_new rel. error 0.447 vs the PC). Measured on a real S23 (v73) and a real S25 (v79) via
AI Hub, and on QAIRT's x86 HTP simulator for v69, v73, v75 and v79. QNN's CPU backend and an fp32-typed
graph on the HTP compute it correctly. `torch.where(tokens == vocab, 1.0, 0.0)` fixes it (0.0063 on the
S23, 0.0062 on the S25) at no speed cost. The bundle above is built with the fix.

## Patch to xn-ptts (against 249c6ec)

- `graphs.py`: the fix above.
- `package.py`: `--device-os` (AI Hub's S23 needed `"13"`), saved to and read back from package.json.
- `bundle.py`: copy `lib/linux-arm64/libptts_text.so` only if it was built (Android-only builds).

```diff
diff --git a/qnn/export/bundle.py b/qnn/export/bundle.py
index fee2432..3ae85f8 100644
--- a/qnn/export/bundle.py
+++ b/qnn/export/bundle.py
@@ -87,7 +87,8 @@ def main():
         shutil.copy(Path(args.cpu) / f, out / "cpu" / f)
     shutil.copy(CHECKPOINT / "tokenizer.json", out / "tokenizer.json")
     shutil.copy(TEXT_FFI / "android-arm64-v8a/libptts_text.so", out / "lib/android-arm64/libptts_text.so")
-    shutil.copy(TEXT_FFI / "linux-arm64/libptts_text.so", out / "lib/linux-arm64/libptts_text.so")
+    if (TEXT_FFI / "linux-arm64/libptts_text.so").exists():  # local: Android-only builds skip the Docker Linux build
+        shutil.copy(TEXT_FFI / "linux-arm64/libptts_text.so", out / "lib/linux-arm64/libptts_text.so")
     phonon.bos_emb.half().numpy().tofile(out / "bos.bin")
 
     catalog = {}
diff --git a/qnn/export/graphs.py b/qnn/export/graphs.py
index 092e1a0..0babe9a 100644
--- a/qnn/export/graphs.py
+++ b/qnn/export/graphs.py
@@ -561,7 +561,9 @@ class Prefill(nn.Module):
         cos, sin, mask = self.tables.flow(pos, self.chunk)
         # One-hot matmul, not a gather: next to the RoPE table gathers, QAIRT 2.50
         # compiled a token gather that read each position's next token on the device.
-        onehot = (tokens.view(-1, 1) == self.vocab[None]).float()
+        # Where, not a bool->float cast: with the cast, the HTP (seen on v73 and v79 devices) computes the
+        # matmul right but the LayerNorm after it wrong, and the text keys and values come out as noise.
+        onehot = torch.where(tokens.view(-1, 1) == self.vocab[None], 1.0, 0.0)
         x = (onehot @ self.embed)[None]
         _, kv_new = self.backbone(x, kv_cache.float(), cos, sin, mask)
         return kv_new.half() if self.io_half else kv_new
diff --git a/qnn/export/package.py b/qnn/export/package.py
index 4716eba..d4afb56 100644
--- a/qnn/export/package.py
+++ b/qnn/export/package.py
@@ -57,17 +57,18 @@ def main():
     ap = argparse.ArgumentParser()
     ap.add_argument("--device", default="Samsung Galaxy S25 (Family)")
     ap.add_argument("--compile-options", default="--qnn_options default_graph_htp_precision=FLOAT16")
+    ap.add_argument("--device-os", default="")
     ap.add_argument("--resume", action="store_true", help="finish the jobs in out/package.json")
     args = ap.parse_args()
     OUT.mkdir(exist_ok=True)
-    device = hub.Device(args.device)
+    device = hub.Device(args.device, args.device_os)
 
     if args.resume:
         # Pick up jobs a previous run submitted, without exporting or uploading again.
         jobs = json.loads((OUT / "package.json").read_text())
         compile_jobs = {n: hub.get_job(jobs["compile"][n]) for n in GRAPHS}
         link_job = hub.get_job(jobs["link"])
-        device = hub.Device(jobs["device"])
+        device = hub.Device(jobs["device"], jobs.get("device_os", ""))
     else:
         ph = load(io_half=True)
         examples = example_inputs(ph)
@@ -81,7 +82,7 @@ def main():
             compile_options=args.compile_options,
         )
         compile_jobs = dict(zip(GRAPHS, jobs_list))
-        jobs = {"device": args.device, "compile": {n: j.job_id for n, j in compile_jobs.items()}}
+        jobs = {"device": args.device, "device_os": args.device_os, "compile": {n: j.job_id for n, j in compile_jobs.items()}}
         for n, j in compile_jobs.items():
             print(n, "compile", j.job_id, j.url, flush=True)
         print("link", link_job.job_id, link_job.url, flush=True)
```
