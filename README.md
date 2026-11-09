# Live Audio Subtitle & Translator (Android)

An Android application that captures **internal system audio** (YouTube, games, media players, podcasts, social video) directly at the OS level—**not from the microphone**—and renders real-time **English subtitles** in a floating, draggable screen overlay.

---

## 🌟 Key Architecture & Features

1. **System Audio Capture (No Microphone, Pure Digital Audio)**:
   - Built on Android 10+ (`API 29+`) `AudioPlaybackCaptureConfiguration` paired with `MediaProjection`.
   - Directly intercepts digital PCM audio streams (`USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`).
   - Zero room echo, zero ambient noise interference.

2. **Movable Floating Overlay (`SYSTEM_ALERT_WINDOW`)**:
   - Renders subtitles on top of full-screen video players, YouTube, games, or social apps.
   - Smooth drag-and-drop gesture support to position anywhere on screen.
   - High-contrast, translucent card with drop shadows for legibility over bright or dark video scenes.
   - Auto-fades during silence.

3. **Dual Translation Architecture**:
   - **Mode A: 100% Offline (On-Device Whisper via ONNX)**:
     - Powered by `sherpa-onnx` and OpenAI Whisper `tiny` or `base` models.
     - Uses Whisper's native `task = "translate"` to perform direct speech-to-English translation from 99+ languages locally on-device. No internet needed.
   - **Mode B: Hybrid Cloud (Groq / OpenAI Whisper API)**:
     - Free/low-cost API key integration for lightning-fast **~150–250ms latency**.
     - Provides instantaneous real-time subtitle sync for fast-paced dialogues.

4. **Android 14+ Ready**:
   - Compliant with Android 14 (API 34) Foreground Service type policies (`foregroundServiceType="mediaProjection"`).

---

## 🚀 How to Build and Sideload

### Option 1: Open in Android Studio
1. Launch Android Studio.
2. Select **Open** and choose `C:\Users\katwe\.gemini\antigravity\scratch\LiveSubtitlesApp`.
3. Let Gradle sync dependencies.
4. Connect your Android phone via USB (with **USB Debugging** enabled in Developer Options).
5. Click **Run** (`Shift + F10`) or select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Option 2: Build via Command Line
```bash
./gradlew assembleDebug
```
The generated APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

To sideload directly to your phone via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or simply transfer the `app-debug.apk` file to your phone (via USB, QuickShare, or Google Drive) and tap to install.

---

## ⚙️ Running the App on Your Phone

1. **Grant Overlay Permission**:
   - On first launch, tap **"Grant Overlay Permission"**.
   - Enable "Allow display over other apps" for Live Subtitles.
2. **Choose Engine**:
   - **Offline (Whisper ONNX)**: Completely on-device.
   - **Hybrid (Groq / OpenAI)**: Enter your free Groq or OpenAI API key for instant (<250ms) translations.
3. **Start Subtitles**:
   - Tap **"Start Subtitle Service"**.
   - Android will display the standard system prompt: *"Live Subtitles will start capturing everything that's displayed on your screen or played on your device"*.
   - Tap **"Start now"**.
4. **Enjoy**:
   - Open any foreign language video (e.g. YouTube, VLC, Twitter, TikTok, browser video).
   - Drag the floating subtitle card to your preferred spot (top, center, or bottom).
