package com.ibbie.catrec_screenrecorcer.data

/**
 * Static FAQ content (English). Expand/collapse is handled in [com.ibbie.catrec_screenrecorcer.ui.faq.FaqScreen].
 */
data class FaqCategory(
    val id: String,
    val title: String,
    val items: List<FaqItem>,
)

data class FaqItem(
    val id: String,
    val question: String,
    val answer: String,
)

object FaqData {
    val categories: List<FaqCategory> = listOf(
        FaqCategory(
            id = "crash_lag",
            title = "Crash & lag",
            items = listOf(
                FaqItem(
                    id = "crash_why",
                    question = "Why does the screen recorder crash while recording?",
                    answer = """
                        Crashes during recording usually come from memory pressure, the system killing the foreground service, or OEM “battery savers” stopping background work.

                        Common causes:
                        • **Low free RAM** — 4K/high-FPS/high-bitrate settings use a lot of memory; try lowering resolution or FPS in Settings → Video.
                        • **Thermal throttling** — long sessions heat the phone; Android may kill heavy processes.
                        • **Aggressive battery optimization** — the app may be restricted from running in the background (see “Xiaomi & other phones”).
                        • **MediaProjection revoked** — if you leave the captured app in single-app mode or the system revokes capture, recording can end abruptly (this may look like a “crash”).
                        • **Storage full** — muxing fails if there is no space left for the temp file or final MP4.
                        • **Third-party overlays or accessibility tools** — rare conflicts with display capture.

                        If it happens often, try Performance mode in Settings, reduce video quality, disable other heavy apps, and exclude CatRec from battery restrictions.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "lag_stutter",
                    question = "Why is my recording laggy or stuttering?",
                    answer = """
                        Lag in the **saved file** is different from lag you **see while recording** (overlay preview is not always representative).

                        Reasons the **output** can stutter:
                        • **CPU/GPU overload** — resolution and FPS beyond what the device can encode in real time.
                        • **Storage speed** — slow internal storage or SD card can’t keep up with the bitrate.
                        • **Thermal limits** — device lowers CPU speed when hot.
                        • **Too many apps** — free RAM before recording.

                        What to try:
                        • Lower **resolution** (e.g. 720p instead of native), **FPS** (30 instead of 60), or **bitrate**.
                        • Turn on **Performance mode** in Settings if available.
                        • Close games and heavy browsers before recording.
                        • Record shorter clips to test whether heat is the issue.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "app_closes",
                    question = "The app closes when I switch to a game or another app",
                    answer = """
                        Screen recording keeps a **foreground service** running. If the system or an OEM task killer stops that service, recording ends.

                        Check:
                        • **Don’t “force stop”** CatRec from system settings while recording.
                        • **Disable battery restrictions** for CatRec (see Xiaomi & other phones).
                        • **Lock the app in recents** on some OEMs (e.g. pull down on the task card and tap “Lock”).
                        • If you use **single-app / window capture** on Android 14+, leaving that window can end capture by design.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "black_preview",
                    question = "Preview looks frozen but timer runs — is it broken?",
                    answer = """
                        Some devices **don’t update a live preview** of the whole screen for privacy or performance reasons. The encoder may still be recording normally.

                        **Check the saved file** in Recordings or your gallery. If the file is fine, the issue is only visual preview.

                        If the **saved file** is also black or frozen, see the “Video” section (black screen / DRM).
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "recording",
            title = "Recording & controls",
            items = listOf(
                FaqItem(
                    id = "random_stop",
                    question = "Why did recording stop randomly?",
                    answer = """
                        Typical reasons:
                        • **You tapped Stop** on the notification, overlay, or in-app control.
                        • **Screen off behavior** — if you enabled “stop when screen turns off” in Settings.
                        • **MediaProjection ended** — the system revoked capture (app closed, policy change, or single-app target gone).
                        • **Low storage** — recording stops when the device can’t write more data.
                        • **Phone call or system dialog** — some devices interrupt capture.
                        • **OEM battery saver** killed the foreground service.

                        Enable **floating controls** or check **Stop behavior** in Settings to match how you use the phone.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "wont_start",
                    question = "I tap Start but nothing happens",
                    answer = """
                        Checklist:
                        1. **Grant the screen capture permission** when Android shows the dialog — tap “Start now” or equivalent.
                        2. **Microphone** — if the app asks for mic permission, allow it if you record with mic.
                        3. **Overlay permission** — required for floating controls; Settings will link you to enable it.
                        4. **Another recorder** — only one MediaProjection session may be active; stop other screen recorders.
                        5. **Work / school profile** — some policies block screen capture.

                        If the permission dialog never appears, restart the phone and try again from the Recording tab.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "clipper_buffer",
                    question = "How does Clipper (rolling buffer) work?",
                    answer = """
                        **Clipper** keeps a rolling buffer of the last few minutes (length is set in Settings). It uses the screen capture permission continuously while buffering.

                        • Tap **Save clip** to write the buffered segment to a file.
                        • **Stop** buffering when you’re done to free resources.
                        • Buffering uses **CPU and storage** similar to recording — if the device struggles, shorten buffer length or use normal Recording mode instead.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "gif_mode",
                    question = "How does GIF mode work?",
                    answer = """
                        **GIF mode** still records an **MP4** first using GIF-friendly quality limits (from your GIF preset). After you stop, the app **builds an animated GIF** and saves it under **Pictures/CatRec/GIFs**.

                        • The **MP4 is kept** as a fallback if GIF creation fails.
                        • **Max length** follows the preset — recording may auto-stop at that limit.
                        • GIF files can be **large**; smaller presets produce smaller files.

                        For best compatibility sharing in chat apps, prefer **medium** or **small** presets.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "orientation",
                    question = "Recording restarts when I rotate the phone",
                    answer = """
                        If **orientation** is set to **Auto**, the app may **split the session** when the display rotates (each segment is finalized separately). This is intentional so width/height match the new orientation.

                        To avoid splits:
                        • Lock **Orientation** in Settings → Video to **Portrait** or **Landscape**, or
                        • Lock **auto-rotate** in your system quick settings while recording.
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "video",
            title = "Video quality",
            items = listOf(
                FaqItem(
                    id = "black_screen",
                    question = "The recording is black or shows only part of the screen",
                    answer = """
                        **Black screen** often means **DRM or secure content**:
                        • **Netflix, Disney+, banking apps, some games** use secure surfaces that Android **does not allow** normal screen recorders to capture — you may get black video or audio-only.

                        Other causes:
                        • **Wrong display** on multi-display devices (rare).
                        • **Single-app mode** — you selected one window; switching away can change what’s captured.
                        • **Encoder failure** — try a different **video encoder** (H.264 vs HEVC) in Settings.

                        There is **no reliable workaround** for banking/DRM video; that’s enforced by the OS.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "blurry",
                    question = "Why does my video look blurry or soft?",
                    answer = """
                        • **Resolution** below your screen’s native size scales up and looks soft — try **Native** or a higher preset.
                        • **Low bitrate** causes compression blur — increase Mbps slightly.
                        • **GIF mode** uses a **preset cap** on resolution and bitrate for smaller files; switch to **Recording** mode for maximum quality.
                        • **Digital zoom** or **battery saver** on some OEMs can reduce rendering quality.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "sync",
                    question = "Audio and video are out of sync",
                    answer = """
                        A small drift can happen on **heavy loads** or with **Bluetooth audio** latency.

                        Try:
                        • **Wired headphones** or **phone speaker** for mic tests.
                        • Lower **resolution/FPS** to reduce encoder load.
                        • Avoid **battery saver** during recording.
                        • If you use **separate mic file**, sync in an editor using the two tracks.

                        If sync is consistently bad on one device, use **Contact us** with your phone model and settings.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "file_size",
                    question = "Files are huge — how do I reduce size?",
                    answer = """
                        • Lower **bitrate** (Mbps) in Settings → Video.
                        • Use **720p** or **480p** instead of native/4K.
                        • **Shorter** recordings.
                        • For sharing quick clips, consider **GIF** mode (preset controls size vs quality).

                        Higher quality always means **larger files** — that’s normal.
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "audio",
            title = "Audio",
            items = listOf(
                FaqItem(
                    id = "no_internal",
                    question = "Internal / app audio is missing or silent",
                    answer = """
                        **Internal audio capture** depends on **Android version**, **app policy**, and **OEM**:

                        • The **captured app** must allow capture (`ALLOW_CAPTURE_BY_ALL` or similar). **Banking**, some **music**, and **DRM** apps block it — you’ll get **silence**.
                        • **Android 10+** is required for many internal-audio paths; older devices may only get mic.
                        • **Bluetooth** routing can be quirky — test with **speaker** output.
                        • Some **Samsung / Xiaomi** builds restrict internal audio further.

                        **Workaround:** enable **Microphone** and place the phone where the speaker is audible (not ideal but works for some games).

                        If you see a **“internal audio silent”** toast, the system delivered only zeros — the app is doing its job; the restriction is upstream.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "no_mic",
                    question = "Microphone audio is not recorded",
                    answer = """
                        Check:
                        1. **Settings → Audio → Microphone** is ON.
                        2. **Runtime permission** — Android must allow **RECORD_AUDIO** (grant in system settings if previously denied).
                        3. **Bluetooth headset mic** — some headsets aren’t used for capture; try **wired** or **built-in mic**.
                        4. **Mute** in the recording notification or overlay — unmute if you paused/muted audio.
                        5. **Separate mic file** — if enabled, listen to the sidecar audio file as well as the video.

                        Test with **Voice Recorder** app to confirm the mic hardware works.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "echo",
                    question = "Echo, double audio, or distorted sound",
                    answer = """
                        • **Speaker + mic** — internal audio and mic together can **double** game sound. Try turning **mic off** if you only need internal audio, or **headphones** to reduce bleed.
                        • **Bluetooth delay** can sound like echo in edits — use wired for critical recordings.
                        • **Gain too high** — move mic farther from speakers or lower system volume.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "volume_low",
                    question = "Recorded volume is very low",
                    answer = """
                        • Raise **media volume** before recording (internal capture follows what the app plays).
                        • **Mic** — speak closer; check if a **phone case** blocks the mic hole.
                        • **Bluetooth** — some devices record at lower levels; compare with speaker output.
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "permissions",
            title = "Permissions & overlay",
            items = listOf(
                FaqItem(
                    id = "overlay_bubble",
                    question = "Floating controls don’t appear",
                    answer = """
                        You must allow **Display over other apps** (overlay) in system settings.

                        Steps (wording varies by OEM):
                        1. Open **Settings → Apps → CatRec** (or Special access → Display over other apps).
                        2. Enable **Allow display over other apps**.
                        3. Return to CatRec and turn **Floating controls** on again.

                        Also enable **floating controls** in CatRec’s own Settings → Controls.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "notifications",
                    question = "Why does CatRec need notification permission?",
                    answer = """
                        **Foreground services** (recording, buffer, overlay) **must** show a notification on modern Android. Without **POST_NOTIFICATIONS** (Android 13+), you may not see controls or the system may block the service.

                        Allow notifications for CatRec in **App info → Notifications**.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "projection_each_time",
                    question = "Do I have to accept screen capture every time?",
                    answer = """
                        Android shows a **one-time** (or per-session) **MediaProjection** consent dialog for privacy.

                        If you use **Authorize / Prepare** from the Recording tab with **floating controls**, CatRec can keep a **prepared** session so the **overlay** can start without the dialog again until you **revoke** it or the system clears it.

                        Fully killing the app or rebooting usually requires **granting again**.
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "files_storage",
            title = "Files & storage",
            items = listOf(
                FaqItem(
                    id = "where_saved",
                    question = "Where are recordings saved?",
                    answer = """
                        By default, videos go to **Movies/CatRec** (or your chosen **SAF folder** if you picked one in Settings). Screenshots use **Pictures/CatRec/Screenshots**, GIFs **Pictures/CatRec/GIFs**.

                        Use the **Recordings** and **Screenshots** tabs inside the app to browse. Your **Gallery** app may also show them after MediaStore scan.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "cant_find",
                    question = "I can’t find my file",
                    answer = """
                        • Check **Recordings** tab — failed recordings may not appear if encoding produced no data.
                        • Open **Files** app → **Movies** / **Pictures** and search **CatRec**.
                        • If you changed **save location**, open that folder in a file manager.
                        • **SD card** removed? Files stay on internal storage unless you moved them.
                    """.trimIndent(),
                ),
            ),
        ),
        FaqCategory(
            id = "oem",
            title = "Xiaomi, Samsung & other phones",
            items = listOf(
                FaqItem(
                    id = "xiaomi_autostart",
                    question = "Xiaomi (MIUI): recording stops or overlay dies in the background",
                    answer = """
                        MIUI is **aggressive** with background limits. Do **all** of the following for CatRec:

                        1. **Settings → Apps → Manage apps → CatRec**  
                           • **Autostart** → **On**  
                           • **Battery saver** → **No restrictions** (not “Save battery”)  
                           • **Other permissions** → allow **Display pop-up** / **Background activity** if shown  

                        2. **Security app → Manage apps → Permissions → Autostart** — enable CatRec.

                        3. **Recent apps** — **lock** CatRec (pull down on the card → padlock) so it isn’t swiped away.

                        4. **Developer options** — disable **MIUI optimization** only if you know the risks (last resort).

                        Without **autostart** and **unrestricted battery**, the system often **kills** the recorder minutes after you leave the app.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "oppo_vivo_realme",
                    question = "Oppo, Realme, Vivo, OnePlus (ColorOS / OxygenOS)",
                    answer = """
                        Look for:
                        • **Battery** → **App battery management** → CatRec → **Don’t optimize** / **Allow background activity**.
                        • **Auto launch** or **Startup manager** → allow CatRec.
                        • **Floating window** permission if the bubble doesn’t show.

                        Names differ by version; search settings for **“battery”**, **“startup”**, and **“auto launch”** for this app.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "huawei",
                    question = "Huawei / Honor (EMUI / MagicOS)",
                    answer = """
                        • **Battery** → **App launch** → CatRec → **Manage manually** → enable **Auto-launch**, **Secondary launch**, **Run in background**.
                        • **Protected apps** or **Launch** settings — add CatRec so it isn’t closed after screen off.

                        Huawei devices may also restrict **internal audio** more than stock Android.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "samsung",
                    question = "Samsung (One UI)",
                    answer = """
                        • **Settings → Apps → CatRec → Battery** → **Unrestricted**.
                        • **Put unused apps to sleep** — exclude CatRec.
                        • **Optimize battery usage** — ensure CatRec is **not** optimized.

                        If **edge panels** or **Game Booster** interfere, test with them disabled.
                    """.trimIndent(),
                ),
                FaqItem(
                    id = "transsion_infinix",
                    question = "Tecno, Infinix, Itel (HiOS / XOS)",
                    answer = """
                        Enable **auto-start** and **allow background activity** in the phone’s **Phone Master** or **App management** app. Disable **app freezer** for CatRec.

                        These brands often kill background services quickly without these toggles.
                    """.trimIndent(),
                ),
            ),
        ),
    )
}
