# MicUp NR — Ulefone setup guide

This build puts the Noise Repellent DSP directly inside MicUp. You do **not**
install the Debian `.deb` or its LV2 file on the phone. That desktop binary is
for x86-64 Linux; this APK contains a fresh ARM64 Android build of the same
Noise Repellent DSP engine.

It is an experimental listening tool, not a medical device and not a substitute
for clinical equipment or diagnosis.

## 1. Install it

1. Copy the MicUp NR 1.1 ARM64 APK to the phone.
2. Open the APK in Files or your browser's downloads.
3. If Android blocks it, allow **Install unknown apps** for that one app, then
   open the APK again.
4. Grant **Microphone** permission. Notification permission is useful because
   Android keeps the audio engine alive as a foreground service.

The package name is `com.micplugin.noiserepellent`, so it can be installed next
to the normal MicUp app.

## 2. Connect and route the hardware

Connect the USB stethoscope microphone and Bluetooth headphones **before**
learning a profile.

1. Pair/connect the headphones and make sure **Media audio** is enabled for
   them in Android's Bluetooth settings.
2. Plug in the USB microphone/adapter. Accept Android's USB-audio prompt if one
   appears.
3. Open MicUp NR and tap the gear.
4. Under **Input Device**, choose the USB device. It may be called **USB Audio
   Device**, **USB Headset Mic**, or the adapter's product name.
5. Under **Monitor Output Device**, choose **Bluetooth Headphones**, **BLE
   Headset**, or the headphones' product name.
6. Return to the main screen. The power button is blue while audio is running.
   Turn **Monitor Output** on.

MicUp remembers both choices. Changing the input deliberately clears the old
noise profile because a profile learned from another microphone would be wrong.

## 3. Learn the unwanted noise

1. Start with the headphone volume low.
2. In **Noise Repellent · Built in**, leave the effect switched on.
3. Hold the chestpiece still and away from the body/machine sound you want to
   hear. Let it capture only the steady room, fan, electrical, and microphone
   noise you want removed.
4. Tap **Learn noise · 3 sec** and remain still for the countdown.
5. Wait for **PROFILE READY**, then place the chestpiece on the target.

Learn again whenever the room noise, microphone gain, adapter, input device, or
chestpiece background changes. The learned profile lives in memory, so learn it
again after the app/service is fully stopped or the phone is restarted.

## 4. Sensible starting controls

The **Stethoscope NR** preset starts here:

| Control | Start at | What it does |
|---|---:|---|
| Quality / latency | Balanced | Enables BM3D/NLM processing at moderate latency |
| Broadband reduction | 18 dB | Maximum depth of broadband subtraction |
| Tonal reduction | 18 dB | Maximum depth for hum and stable tones |
| Listen gain | 10 dB | Amplification after denoising |
| Smoothing strategy | BM3D | Collaborative cleanup of musical-noise artifacts |
| Smoothing | 35% | Reduces musical/warbling artifacts |
| Signal protection | 70% | Protects wanted sound from subtraction |
| Noise whitening | 0% | Changes the character of leftover hiss |
| Profile statistic | 0.5 | Chooses a more assertive learned profile |
| Broadband profile threshold | 0 dB | Moves the learned noise threshold up/down |
| Transient protection | On | Preserves taps and sound onsets |

Adjust **Listen gain** last and gradually. The app limits the digital output,
but it cannot control the headphones' own amplifier or protect against an
uncomfortably loud physical volume setting.

### If too much noise remains

- Learn again with only the unwanted noise present.
- Raise **Broadband reduction** toward 24–30 dB.
- Raise **Broadband profile threshold** by 1–3 dB.
- Raise **Profile statistic** slightly.

### If wanted sound becomes watery, hollow, or disappears

- Lower **Broadband reduction** toward 12–18 dB.
- Raise **Signal protection** toward 80–90%.
- Add a little **Smoothing**.
- Lower **Broadband profile threshold**.
- Make sure the wanted body/mechanical sound was not present during learning.

**Hear removed noise** is diagnostic: it plays what the algorithm is throwing
away. Turn it back off for normal listening.

## Bluetooth delay

Bluetooth media headphones buffer audio, so noticeable delay is normal even
when the app reports a small processing latency. Use wired headphones for the
lowest delay. The delay changes timing only; it does not stop profile learning
or denoising.

## Quick fault finding

| Symptom | Check |
|---|---|
| No input meter movement | Select the USB device; unplug/reconnect it while the phone is unlocked; check the adapter supports USB audio input |
| Meter moves but no sound | Turn Monitor Output on; select the headphones explicitly; check Android media volume |
| Sound comes from the phone | Re-select the Bluetooth device under Monitor Output Device |
| `START AUDIO FIRST` | Grant microphone permission and tap the top power button |
| `ENGINE ERROR` | Fully close/reopen the app; if it repeats, capture the crash report and phone log |
| Feedback/howling | Lower headphone volume, improve acoustic isolation, and keep the headphones away from the chestpiece/mic |
| Bad profile after reconnecting | Tap Learn again; device/environment changes require a new profile |

## What was integrated

- MicUp base revision: `aacf0b087dfd77eb73c69633d305f1a94386d0d6`
- libspecbleach / Noise Repellent DSP: v0.4.1,
  `ab4ae8b4be74f5c52c5a3386a6d9bbea29fbd129`
- Oboe: 1.8.1, built from source with NDK 26
- Target: Android API 26+, ARM64 (`arm64-v8a`)

See `THIRD_PARTY_NOTICES.md` and the included license files for source and
licensing details.
