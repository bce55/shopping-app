# VN Transcriber

Offline transcription of WhatsApp voice notes on Android (built for Samsung Galaxy).
No cloud, no API keys, no forwarding. whisper.cpp runs on the phone.

> **Status: not built or run on a device yet.** The native JNI file compiles against
> whisper.cpp v1.9.5 headers. The Kotlin and Gradle parts were written to current APIs
> but have never been through a real build. Expect a short round of fixes on first build.

---

## Read this first (limits you can't code around)

1. **Sideload only.** `MANAGE_EXTERNAL_STORAGE` plus an accessibility service used for
   something other than accessibility will get rejected on Google Play. This is a
   personal APK.
2. **`READ_MEDIA_AUDIO` is useless here.** WhatsApp puts `.nomedia` files in its media
   folders, so voice notes never appear in MediaStore. Raw file access ("All files
   access") is the only way to see them.
3. **Hebrew on `tiny`/`base` is weak.** Expect many wrong words. `small` (q5_1, ~190 MB)
   is the smallest model that is usable for Hebrew. It is the default.
4. **No sender name.** The file is called `PTT-20261007-WA0012.opus`. Nothing on disk links
   it to a chat or contact (that lives in WhatsApp's encrypted database). Cards show time,
   length and language, not who sent it.
5. **Your own sent voice notes are in the same folder.** They get transcribed too.
6. **Only downloaded notes exist on disk.** If WhatsApp's auto-download is off for some
   chats, nothing appears until you tap download.
7. **Speed.** On a recent Galaxy S, `small-q5_1` takes roughly 0.3x-1x the audio length.
   On a mid-range A-series, expect slower than real time for long notes. This is an
   estimate, not a measurement.

---

## Architecture

```
 WhatsApp writes PTT-*.opus
          │
          ▼
 ┌─────────────────────────────── WhatsAppFileWatcher (foreground service, specialUse)
 │  FileObserver (inotify) ─┐
 │  15 s poll (safety net) ─┴─► scan(): new files, newer than first-run cutoff,
 │                               not already processed  ──► Channel<File>
 │                                                              │ one at a time
 │  awaitComplete(): wait until file size stops changing        ▼
 │  TranscriptionEngine.transcribe()
 │     ├─ AudioDecoder (Dispatchers.IO): MediaCodec Opus → 16 kHz mono float
 │     └─ WhisperLib JNI (Dispatchers.Default, Mutex): whisper_full()
 └───────────────┬──────────────────────────────────────────────
                 ├─► Notifications.transcript()   (always: lock screen / fallback)
                 └─► OverlayService.show()        (floating card stack)
                              ▲
   WhatsAppAccessibilityService ── "is WhatsApp in front?" (optional)
```

Design choices worth knowing:

- **Two detection paths.** inotify on `/sdcard` runs over a FUSE mount. Whether events
  from another app's writes reach us depends on the Android/One UI build. The 15 s poll
  makes detection reliable either way; the observer just makes it fast when it works.
- **No WorkManager.** Periodic work has a 15 minute minimum. Useless for "on arrival".
- **`specialUse` FGS type**, not `dataSync`. Android 15 caps `dataSync` at 6 h per day.
- **Inference on `Dispatchers.Default`, not `IO`.** It is CPU work. The IO pool is for
  threads that sit blocked on disk/network. File decoding does run on `IO`.
- **Model stays loaded** while the watcher runs (~200-300 MB RAM for small). Freed when
  you stop the watcher.
- **Accessibility service is optional.** Without it, cards show over any app. With it,
  cards only show over WhatsApp and wait for you to open WhatsApp.
- **UTF-8 bytes across JNI**, not `NewStringUTF`. That call aborts on emoji, which
  Whisper sometimes outputs.

### Files

| File | Job |
|---|---|
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge to whisper.cpp |
| `app/src/main/cpp/CMakeLists.txt` | Builds whisper.cpp + JNI into `libwhisper_jni.so` |
| `WhatsAppFileWatcher.kt` | Foreground service: detect, queue, transcribe, notify |
| `VoiceNoteDirs.kt` | WhatsApp / WhatsApp Business folder paths |
| `AudioDecoder.kt` | Opus/M4A → 16 kHz mono float via MediaCodec |
| `TranscriptionEngine.kt` | Coroutine wrapper around the whisper context |
| `WhisperLib.kt` | `external` JNI declarations |
| `ModelManager.kt` | Model from assets, Download folder, or one-time download |
| `OverlayService.kt` | Draggable card stack (`TYPE_APPLICATION_OVERLAY`) |
| `WhatsAppAccessibilityService.kt` | Tracks if WhatsApp is in front |
| `MainActivity.kt` | Setup checklist + "transcribe newest now" test button |
| `BootReceiver.kt` | Restarts watcher after reboot / update |

---

## Build

### 1. Tools

- Android Studio (Ladybug or newer).
- SDK Manager → SDK Tools: **NDK 27.2.12479018** and **CMake 3.22.1**.
  (Other NDK versions work; change `ndkVersion` in `app/build.gradle.kts`.)

### 2. Get whisper.cpp

This project should live in its own git repo. From the project root:

```bash
git init   # if it is not a repo yet
git submodule add https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
git -C third_party/whisper.cpp checkout v1.9.5
```

Pin a tag. whisper.cpp changes its build often, and `main` can break you.

### 3. Open and build

1. Android Studio → Open → this folder. Let Gradle sync (first time takes a while).
2. Plug in the phone with USB debugging on.
3. Run ▶ `app`. The first native build compiles whisper.cpp, a few minutes.

The native code is always built with Release flags, even in debug APKs. Debug-built
whisper.cpp is about 10x slower.

### 4. Get the model onto the phone

Pick one. The app checks them in this order.

**A. Fully offline, via adb (best):**
```bash
curl -L -o ggml-small-q5_1.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
adb push ggml-small-q5_1.bin /sdcard/Download/
```
Then in the app tap **6. Get model**. It copies the file into private storage. You can
delete it from Download after.

**B. Bundle in the APK:** put the `.bin` in `app/src/main/assets/models/`. The APK grows
by the model size. `.gitignore` already excludes it.

**C. In-app download:** tap **6. Get model** with nothing in Download. One HTTPS
download, then it never uses the network again. To make the app provably offline,
use A or B and delete the `INTERNET` line from the manifest.

Other model files: `ggml-base-q5_1.bin`, `ggml-small-q8_0.bin`. Must be **multilingual**
(no `.en` in the name), or Hebrew will not work at all.

---

## Phone setup (in app order)

1. **All files access** → turn on.
2. **Display over other apps** → turn on.
3. **Notifications** → allow.
4. **Battery optimization** → Allow.
5. **Accessibility (optional).** On Android 13+ a sideloaded app's accessibility toggle is
   greyed out ("Restricted setting"). Fix: Settings → Apps → VN Transcriber → ⋮ (top right)
   → **Allow restricted settings**. Then Settings → Accessibility → Installed apps →
   VN Transcriber → On.
6. **Get model.**
7. **Start watcher.**
8. Tap **Transcribe newest voice note now** to check everything works before trusting it.

### Samsung One UI: stop it killing the service

One UI is aggressive. Do all of these:

1. Settings → Apps → VN Transcriber → Battery → **Unrestricted**.
2. Settings → Battery → Background usage limits →
   - turn **off** "Put unused apps to sleep",
   - **Never sleeping apps** → add VN Transcriber.
3. Recents screen → tap the app icon on its card → **Keep open** (lock).
4. Settings → Device care → Auto optimization → off "Auto restart" if you rely on long
   uptime, or accept that the boot receiver restarts the watcher.
5. Reference: https://dontkillmyapp.com/samsung

---

## Troubleshooting

| Symptom | Check |
|---|---|
| "WhatsApp voice notes folder found" ❌ | All-files access off, or WhatsApp still on old storage. Paths are in `VoiceNoteDirs.kt`. |
| Nothing happens on new note | `adb logcat -s WhatsAppFileWatcher TranscriptionEngine whisper_jni`. Should log "Watching [...]". Up to 15 s delay if inotify is silent. |
| Gibberish Hebrew | Use `small`, set language to עברית instead of Auto. |
| English words turn into Hebrew letters | Language is forced to `he`. Use Auto for mixed chats. |
| Very slow | Make sure it is arm64 and Release. Try enabling `GGML_CPU_ARM_ARCH` in `CMakeLists.txt` (Galaxy S10 / 2019 A-series and newer only). |
| App killed overnight | Samsung battery steps above. |

---

## What is not built (yet)

- **Transcribe on play.** Possible with the accessibility service reading WhatsApp's view
  tree, but WhatsApp's view IDs change between versions. It would break often. Arrival-time
  transcription covers the same need.
- **History screen.** Transcripts live in notifications and cards only. Add Room if you
  want search.
- **Long notes.** Audio over 15 minutes is cut.
