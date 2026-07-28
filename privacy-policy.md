# Privacy Policy

**CatRec – Screen Recorder** · Last updated: July 23, 2026

---

## Overview

CatRec – Screen Recorder ("the App", "we", "our") is developed by **ibbie**. This Privacy Policy explains what information the App processes, how it is used, and your choices.

The App is built to work primarily on your device. Screen recordings, screenshots, and clips you create are stored locally and are not uploaded to our servers by the App. Third-party services described below (Google AdMob, Firebase, Google Play) may process limited data when you use those features.

---

## Information We Do Not Collect From You Directly

We do not operate user accounts and we do not ask for your name or email to use the App. We do not collect, sell, or rent your screen recordings, screenshots, microphone audio, camera preview, or contacts.

If you choose **Contact us** in the App, your email app sends a message you write to us. That message may include optional attachments and automatic diagnostic lines (app version, device model, Android version) added by the App to help support. We only receive what you choose to send.

---

## Data Stored on Your Device

The App stores the following locally (for example in Android `SharedPreferences`, DataStore, and MediaStore paths under `Movies/CatRec` and `Pictures/CatRec`):

- Recording and UI preferences (resolution, FPS, bitrate, audio sources, overlay options, theme, language, and similar settings)
- Your recordings, screenshots, GIF exports, and optional separate microphone sidecar files
- Purchase state for **Remove Ads** (so ads stay off after you buy it)
- Optional temporary **Pro** unlock timestamps when you watch a rewarded ad
- A random anonymous identifier used only when analytics/crash reporting is enabled (see below)

You can delete media from within the App or from your device file manager at any time.

---

## Permissions

The App requests Android permissions only to provide its features (recording, overlay controls, library, camera bubble, notifications, and related tools). A detailed permission-by-permission explanation is in our [Permissions Disclosure](https://github.com/ibbisdead/CatRec-Android/blob/main/permissions-disclosure.md).

Permissions are not used to build a profile of you or to upload your recordings to us.

### Optional Accessibility Service: CatRec game mic helper

CatRec includes an optional Android accessibility service named **CatRec game mic helper**. CatRec is not an accessibility tool, and this service is not required for ordinary screen recording.

The service supports one user-facing recording feature: on supported Android devices, it helps CatRec keep access to the microphone while a foreground game or chat app is also using the microphone. This lets CatRec record the microphone track while teammates can continue to hear the user in game or voice chat. The user must deliberately enable the service in Android Accessibility settings and can disable it there at any time.

The accessibility service itself does not record audio. Microphone audio is captured only by CatRec’s recording engine, with the separate Android `RECORD_AUDIO` permission, after the user starts a recording with microphone audio enabled.

CatRec limits this accessibility service as follows:

- It cannot retrieve window content (`canRetrieveWindowContent=false`).
- It cannot perform gestures (`canPerformGestures=false`).
- It does not read screen text, inspect app interfaces, click buttons, type, scroll, or change settings.
- Android may deliver window-state-change accessibility event callbacks containing limited metadata such as the foreground app’s package name; CatRec ignores those callbacks and does not collect, store, transmit, or share their contents.
- No personal or sensitive data is collected, stored, or shared through the AccessibilityService API.

Enabling the service does not upload microphone audio, recordings, accessibility events, or screen content to the Developer. Recordings and microphone tracks remain stored locally unless the user chooses to share them.

---

## Advertising (Google AdMob)

On the free tier, the App may show **banner**, **app open**, and **rewarded** ads through **Google AdMob**. If you purchase **Remove Ads**, ad loading is disabled.

AdMob may collect device and ad-interaction data to deliver and measure ads, including:

- Advertising ID (resettable in Android Settings → Privacy → Ads)
- Device and OS information
- IP address (often used for coarse location such as country)
- Ad impressions and clicks

This data is processed by Google under its policies: [https://policies.google.com/privacy](https://policies.google.com/privacy)

In **Settings**, you can turn off **Personalized ads**. When off, the App requests non-personalized ads (`npa=1`) from AdMob. You can also limit ad personalization in system settings.

---

## Analytics & Crash Reporting (Firebase)

The App uses **Firebase Analytics** and **Firebase Crashlytics** (Google) to understand crashes and improve stability. When enabled, this may include:

- Crash stack traces and diagnostic logs
- App version, device model, and OS/API level
- General usage events (for example feature flows), not the content of your recordings
- An anonymous per-install user ID generated by the App (not your name or email)

You can disable **Usage analytics** in **Settings**. When disabled, Analytics and Crashlytics collection are turned off and Firebase user IDs are cleared. Crash reporting preferences stay aligned with that toggle.

Firebase is governed by Google’s privacy policy: [https://policies.google.com/privacy](https://policies.google.com/privacy)

---

## In-App Purchases (Google Play)

Purchases such as **Remove Ads** and optional **Support me** tips are processed by **Google Play Billing**. Payment and purchase records are handled by Google; we receive only what Play provides to confirm entitlements (for example that ads should remain removed). We do not receive your full payment card details.

---

## Internet Access

The App uses network access for ads (when not removed), Play Billing, and Firebase when analytics is enabled. Recording itself does not require uploading your video to our servers.

---

## Children's Privacy

The App is not directed at children under 13. We do not knowingly collect personal information from children. If you believe a child has contacted us with personal information, email us and we will take appropriate steps.

---

## Changes to This Policy

We may update this Privacy Policy from time to time. The "Last updated" date at the top will change when we do. Continued use of the App after changes means you accept the updated policy.

---

## Contact Us

Questions about this Privacy Policy:

- **Email:** [ibbisdead@proton.me](mailto:ibbisdead@proton.me)
- **YouTube:** [youtube.com/@ibbie](https://youtube.com/@ibbie)
- **Google Play** listing for CatRec – Screen Recorder

---

© 2026 ibbie · CatRec – Screen Recorder
