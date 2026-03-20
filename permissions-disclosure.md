# CatRec — Permissions Disclosure

This document explains every permission CatRec requests, why it is needed, and how it is used. CatRec does not collect, upload, or share any personal data. All recordings and screenshots are stored locally on your device.

---

## Permissions Used

### `FOREGROUND_SERVICE`
**Why needed:** Android requires this permission for any app that runs a long-lived background task. CatRec uses it to keep the screen recording and overlay services active while you are using other apps.

### `FOREGROUND_SERVICE_MEDIA_PROJECTION`
**Why needed:** Required on Android 10+ to run a Media Projection foreground service (the service that captures your screen). Without it, the system would terminate the recording service while the app is in the background.

### `FOREGROUND_SERVICE_MICROPHONE`
**Why needed:** Required on Android 10+ when a foreground service accesses the microphone. CatRec uses it to record your voice alongside the screen capture when microphone audio is enabled.

### `FOREGROUND_SERVICE_CAMERA`
**Why needed:** Required on Android 10+ when a foreground service accesses the camera. CatRec uses it to show a live camera preview overlay (webcam bubble) on top of your screen during recording.

### `POST_NOTIFICATIONS`
**Why needed:** Required on Android 13+ to display notifications. CatRec uses notifications to:
- Show ongoing recording / buffer status.
- Provide quick Stop / Pause / Mute controls without switching apps.
- Show a completion notification with the recording thumbnail after stopping.

### `RECORD_AUDIO`
**Why needed:** Allows CatRec to record audio from your device's microphone. This is only used when you enable microphone audio in settings. Your audio is saved directly to your device and never uploaded.

### `CAPTURE_AUDIO_OUTPUT`
**Why needed:** Allows CatRec to capture internal device audio (system sounds and app audio). This is only used when you enable "Internal Audio" in settings. This permission is reserved for screen-recorder use cases by Android.

### `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`
**Why needed:** Allows CatRec to display your saved recordings in the Library tab. These permissions are only used to read video files from the `Movies/CatRec` folder on your device; CatRec never reads unrelated files.

### `WRITE_EXTERNAL_STORAGE`
**Why needed:** Required on Android 8 and below to save recordings to external storage. On Android 9+, CatRec uses the MediaStore API instead and this permission is not requested.

### `WAKE_LOCK`
**Why needed:** Allows CatRec to prevent the screen from turning off during recording when you enable the "Keep Screen On" option in settings. It is never acquired without your explicit opt-in.

### `SYSTEM_ALERT_WINDOW` (Draw Over Other Apps)
**Why needed:** Allows CatRec to display the floating controls overlay (the draggable bubble with pause, stop, mute, and screenshot buttons) on top of other apps. This is the core feature that lets you control recordings without switching apps. You grant this permission manually in Android Settings.

### `CAMERA`
**Why needed:** Allows CatRec to show a live camera preview as a floating overlay during recording (webcam/face-cam bubble). It is only accessed when you enable the Camera Overlay feature. The camera is never accessed in the background without your knowledge.

---

## Data Practices

| Data type | Collected? | Uploaded? |
|-----------|-----------|----------|
| Screen recordings | Saved locally only | Never |
| Screenshots | Saved locally only | Never |
| Microphone audio | Saved locally only | Never |
| Device identifiers | No | No |
| Usage analytics | Optional, opt-out available | No |

---

## Contact

If you have questions about these permissions, please contact: **ibbiedead@gmail.com**

_Last updated: March 2026_
