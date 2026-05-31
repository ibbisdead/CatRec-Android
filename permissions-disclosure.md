# CatRec — Permissions Disclosure

This document explains every permission CatRec requests, why it is needed, and how it is used. CatRec does not upload your recordings or screenshots to developer servers. Optional analytics and ads are described in the [Privacy Policy](https://github.com/ibbisdead/CatRec-Android/blob/main/privacy-policy.md).

---

## Permissions Used

### `INTERNET`
**Why needed:** Loads ads (free tier), communicates with Google Play Billing, and sends Firebase Analytics/Crashlytics data when you leave usage analytics enabled. Not used to upload your recordings to us.

### `com.android.vending.BILLING`
**Why needed:** Processes in-app purchases (**Remove Ads**, optional **Support me**) through Google Play.

### `FOREGROUND_SERVICE`
**Why needed:** Android requires this for long-running background work. CatRec uses foreground services while recording or showing the floating overlay.

### `FOREGROUND_SERVICE_MEDIA_PROJECTION`
**Why needed:** On Android 10+, required for the Media Projection foreground service that captures your screen (and, when enabled, internal app audio via playback capture tied to the same projection).

### `FOREGROUND_SERVICE_MICROPHONE`
**Why needed:** On Android 10+, required when a foreground service records from the microphone (optional mic track or mic-only audio).

### `FOREGROUND_SERVICE_CAMERA`
**Why needed:** On Android 10+, required when the overlay foreground service shows the live camera preview bubble.

### `POST_NOTIFICATIONS`
**Why needed:** On Android 13+, required to show notifications for ongoing recording/buffer status, quick controls (stop, pause, mute, screenshot), and completion notices.

### `RECORD_AUDIO`
**Why needed:** Records microphone audio when you enable mic audio or separate mic recording. Audio is saved on your device only.

### Internal audio (playback capture — no separate manifest permission)
**How it works:** When you enable **Internal audio** on Android 10+, CatRec captures app/system playback using Android’s **audio playback capture** API together with your approved screen-capture (Media Projection) session—not a standalone `CAPTURE_AUDIO_OUTPUT` permission. Some apps block capture or use unsupported audio paths; the App may notify you or offer fallbacks where supported.

### `READ_MEDIA_VIDEO` · `READ_MEDIA_IMAGES` · `READ_MEDIA_VISUAL_USER_SELECTED` (Android 13+)
**Why needed:** Lets the in-app **Recordings** and **Screenshots** libraries find videos and images CatRec saved (including after reinstall, when MediaStore indexing requires read access). On Android 14+, partial visual access via **Select photos and videos** is supported when you choose it.

### `READ_MEDIA_AUDIO` (Android 13+)
**Why needed:** Discovers optional microphone sidecar files (`.m4a` under CatRec music/recording paths) so the library can show when a separate mic track exists. Not used to scan your entire music library.

### `READ_EXTERNAL_STORAGE` (Android 12 and below)
**Why needed:** Same library and MediaStore discovery on older Android versions. Not requested on Android 13+.

### `WRITE_EXTERNAL_STORAGE` (Android 8 and below)
**Why needed:** Saves recordings to external storage on very old devices. On Android 9+, CatRec uses MediaStore instead and does not request this.

### `WAKE_LOCK`
**Why needed:** Optional **Keep screen on** during recording so the display does not sleep. Only used when you enable that setting.

### `SYSTEM_ALERT_WINDOW` (Display over other apps)
**Why needed:** Shows the floating controls bubble and overlay UI on top of other apps. You grant this manually in Android Settings.

### `CAMERA`
**Why needed:** Live camera preview overlay during recording or in settings preview. Only used when you enable the camera overlay feature.

### `BLUETOOTH` (Android 11 and below) · `BLUETOOTH_CONNECT` (Android 12+)
**Why needed:** Required on newer Android versions so the Google Mobile Ads SDK can initialize reliably on some devices. CatRec does not pair with or control your Bluetooth accessories for recording.

### `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
**Why needed:** Optional prompt so you can exempt CatRec from aggressive battery restrictions on some OEMs, reducing recording stops in the background. You choose whether to allow it.

---

## Picking files from your gallery

When you choose an image or video (watermark, merge, GIF tool, feedback attachments, etc.), CatRec uses Android’s **Photo Picker** where available so you select specific items. Broad library read permissions above are for **your CatRec media** in the app libraries, not for silently scanning all photos on your phone for unrelated purposes.

---

## Data practices summary

| Data type | Stored on device? | Uploaded by CatRec to developer servers? |
|-----------|-------------------|------------------------------------------|
| Screen recordings & screenshots | Yes | No |
| Microphone / internal audio tracks | Yes | No |
| Settings & purchase flags | Yes | No |
| Ad / analytics data (if enabled) | Processed by Google | Via Google services only |
| Support email you send | In your mail app | Only if you send it to us |

---

## Contact

Questions about these permissions:

- **Email:** [ibbisdead@proton.me](mailto:ibbisdead@proton.me)
- **YouTube:** [youtube.com/@ibbie](https://youtube.com/@ibbie)

---

_Last updated: May 27, 2026_
