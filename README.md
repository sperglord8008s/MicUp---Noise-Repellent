<div align="center">

# 🎙️ MicUp NR. this may be AI slip I am not a programmer!!!

**MicUp with learned-noise subtraction built in for ARM64 Android**

[![Android](https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square&logo=android)](https://github.com/papergray/MicUp/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

[Phone setup guide](MICUP_NR_GUIDE.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

</div>

---

## What is MicUp?

MicUp NR is a custom fork of MicUp for live listening with a USB stethoscope
microphone. It captures a manual background-noise profile, subtracts it with
libspecbleach (the DSP engine behind Noise Repellent), then applies adjustable
listen gain. The desktop Debian/LV2 binary is not used or required.

This is an experimental listening tool, not a medical device.

No PC required. No monthly subscription.

---

## Features

-  **Learned-noise subtraction** — three-second manual profile capture, reduction, smoothing, signal protection, and residual-listen controls
-  **Stethoscope listen gain** — smoothed post-denoiser gain with bounded output
-  **USB input and Bluetooth output selection** — remembers both routes and clears stale profiles when the microphone changes
-  **Built-in DSP chain** — Noise Gate, 10-band EQ, Compressor, Reverb, Pitch Shifter
-  **Plugin support** — Load LV2, CLAP, and VST3 native plugins (`.so`, `.clap`, `.lv2`)
-  **Open plugin files** — Tap a plugin file in your file manager to import it directly
-  **Monitor toggle** — Hear your processed audio through headphones, or silence it while still routing to other apps
-  **Shizuku support** — ADB-level ALSA loopback routing without full root
-  **Three virtual mic tiers** — VoIP stream (no root), Shizuku (ADB), or Magisk (full root)
-  **Live VU meters** — Input, output, and gain reduction metering
-  **Preset system** — Save and load your effect configurations
-  **Built-in crash reporter** — Crash logs auto-shared for easy bug reporting

---

## Download

Use the ARM64 APK supplied with this source snapshot. It has the separate
package id `com.micplugin.noiserepellent`, so it can coexist with upstream
MicUp.

No Play Store. No sign-in. Just install and go.

> **Enable "Install from unknown sources"** in Android Settings → Apps → Special app access before installing.

---

## How to Use

1. Install the APK and grant microphone permission.
2. Connect the USB microphone and Bluetooth/wired headphones.
3. In Settings, choose the USB **Input Device** and headphone **Monitor Output Device**.
4. Under **Noise Repellent · Built in**, capture three seconds of unwanted noise.
5. Wait for **PROFILE READY**, then raise **Listen gain** gradually.

See [MICUP_NR_GUIDE.md](MICUP_NR_GUIDE.md) for the exact Ulefone setup and
troubleshooting steps.

### Loading Plugins

- Open your file manager, navigate to your plugin `.so` / `.clap` / `.lv2` file
- Tap it → select **MicUp** → plugin is imported and added automatically
- Or go to **Settings → Manage Plugin Paths** to add a folder to scan

### Shizuku (optional, no root needed)

Shizuku gives MicUp ADB-level access for better audio routing:

1. Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) from Play Store
2. Start Shizuku via wireless debugging (Android 11+)
3. Open MicUp → Settings → Shizuku → tap **Grant**

---

## Building from Source

**Requirements:** Android Studio/Gradle 8.6, Android SDK 34, NDK
26.3.11579264, CMake 3.22.1, and JDK 17. The supplied source archive includes
the pinned native dependencies under `third_party/`; a Git checkout fetches
the same exact revisions automatically when that directory is absent.

```bash
cd MicUp-NoiseRepellent
RELEASE_TAG=v1.0.0 ./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Supported Formats

| Format | Extension | Notes |
|--------|-----------|-------|
| LV2 | `.lv2` `.so` | Requires `liblilv` |
| CLAP | `.clap` `.so` | CLAP 1.2.1 |
| VST3 | `.so` | Experimental |
| APK Plugin | installed app | Via AIDL interface |

---

## Requirements

- Android 8.0+ (API 26)
- ARM64 (`arm64-v8a`)
- Microphone permission
- For Shizuku tier: Android 11+
- For Root tier: Magisk

---

## License

MicUp code is MIT licensed — see [LICENSE](LICENSE). The built-in
libspecbleach DSP is LGPL-2.1-or-later and Oboe is Apache-2.0. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the license copies under
`app/src/main/assets/licenses/`.

---

<div align="center">
Made for Android · Built with libspecbleach, Oboe, C++17, and Jetpack Compose
</div>
