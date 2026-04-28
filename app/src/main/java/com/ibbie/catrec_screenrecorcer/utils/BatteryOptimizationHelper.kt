package com.ibbie.catrec_screenrecorcer.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Utility for battery-optimization exemption management.
 *
 * Responsibilities:
 *  - Detect whether the app is already exempted from battery optimization.
 *  - Identify known "aggressive killer" OEMs and produce a prioritized list of
 *    intents that open their proprietary auto-start / battery management screens.
 *  - Launch the standard Android direct-exemption prompt or general battery list.
 *  - Open the dontkillmyapp.com visual guide as a last resort.
 *
 * All OEM intent lists are ordered from most-specific / most-reliable to
 * least-specific so that [launchOemSettings] tries the best match first.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    // ── OEM identification ────────────────────────────────────────────────────

    enum class KnownOem { XIAOMI, HUAWEI, SAMSUNG, OPPO, VIVO, ONEPLUS, REALME, GENERIC }

    data class OemKillerInfo(
        val oem: KnownOem,
        /** Human-readable OS/skin name shown in the UI. */
        val displayName: String,
        /** Ordered list of intents to attempt; [launchOemSettings] tries each in sequence. */
        val settingsIntents: List<Intent>,
        /** URL to the dontkillmyapp.com page for this manufacturer. */
        val webGuideUrl: String,
    )

    // ── State query ───────────────────────────────────────────────────────────

    fun isExempted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ── OEM detection ─────────────────────────────────────────────────────────

    fun detectOem(): KnownOem {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> KnownOem.XIAOMI
            "huawei" in m || "honor" in m -> KnownOem.HUAWEI
            "samsung" in m -> KnownOem.SAMSUNG
            "oppo" in m -> KnownOem.OPPO
            "vivo" in m -> KnownOem.VIVO
            "oneplus" in m -> KnownOem.ONEPLUS
            "realme" in m -> KnownOem.REALME
            else -> KnownOem.GENERIC
        }
    }

    fun isAggressiveKiller(): Boolean = detectOem() != KnownOem.GENERIC

    /**
     * Returns an [OemKillerInfo] for known aggressive-killer manufacturers, or null for
     * generic devices where the standard Android battery-optimization prompt is sufficient.
     */
    fun getOemKillerInfo(): OemKillerInfo? =
        when (detectOem()) {
            KnownOem.XIAOMI -> OemKillerInfo(
                KnownOem.XIAOMI,
                "MIUI / HyperOS",
                listOf(
                    // MIUI 12+ / HyperOS: Autostart manager (most reliable entry point)
                    Intent().setComponent(ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )),
                    // MIUI Powerkeeper: per-app battery restriction list
                    Intent().setComponent(ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
                    )),
                    // Fallback: open MIUI Security Center home
                    Intent().setPackage("com.miui.securitycenter"),
                ),
                "https://dontkillmyapp.com/xiaomi",
            )

            KnownOem.HUAWEI -> OemKillerInfo(
                KnownOem.HUAWEI,
                "EMUI / HarmonyOS",
                listOf(
                    // EMUI 9+: Startup manager — controls which apps can start on boot / in background
                    Intent().setComponent(ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    )),
                    // EMUI 8 and earlier: App Launch management
                    Intent().setComponent(ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                    )),
                    // Older EMUI: Protected Apps list (prevents system kill)
                    Intent().setComponent(ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    )),
                ),
                "https://dontkillmyapp.com/huawei",
            )

            KnownOem.SAMSUNG -> OemKillerInfo(
                KnownOem.SAMSUNG,
                "Samsung One UI",
                listOf(
                    // One UI 3+: Device Care → Battery → App Power Management
                    Intent().setComponent(ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity",
                    )),
                    // One UI 2.x alternative package
                    Intent().setComponent(ComponentName(
                        "com.samsung.android.sm.policy",
                        "com.samsung.android.sm.policy.battery.BatteryActivity",
                    )),
                    // Older Samsung: Device Care main screen
                    Intent().setComponent(ComponentName(
                        "com.samsung.android.devicecare",
                        "com.samsung.android.devicecare.activity.MainActivity",
                    )),
                ),
                "https://dontkillmyapp.com/samsung",
            )

            KnownOem.OPPO -> OemKillerInfo(
                KnownOem.OPPO,
                "ColorOS",
                listOf(
                    // ColorOS 7+: Startup Manager (coloros package)
                    Intent().setComponent(ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    )),
                    // ColorOS 11+: renamed to oplus package
                    Intent().setComponent(ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.internative.permission.startup.StartupAppListActivity",
                    )),
                    // Fallback: open ColorOS Security Center
                    Intent().setPackage("com.coloros.safecenter"),
                ),
                "https://dontkillmyapp.com/oppo",
            )

            KnownOem.VIVO -> OemKillerInfo(
                KnownOem.VIVO,
                "FuntouchOS / Origin OS",
                listOf(
                    // FuntouchOS: Background App Management
                    Intent().setComponent(ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    )),
                    // iQOO (Vivo sub-brand): Whitelist Manager
                    Intent().setComponent(ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    )),
                ),
                "https://dontkillmyapp.com/vivo",
            )

            KnownOem.ONEPLUS -> OemKillerInfo(
                KnownOem.ONEPLUS,
                "OxygenOS",
                listOf(
                    // OxygenOS: App Auto-Launch management
                    Intent().setComponent(ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    )),
                ),
                "https://dontkillmyapp.com/oneplus",
            )

            KnownOem.REALME -> OemKillerInfo(
                KnownOem.REALME,
                "Realme UI",
                listOf(
                    // Realme UI (uses realme.safecenter in newer builds)
                    Intent().setComponent(ComponentName(
                        "com.realme.safecenter",
                        "com.realme.safecenter.permission.startup.StartupAppListActivity",
                    )),
                    // Older Realme UI shares the OPPO coloros package
                    Intent().setComponent(ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    )),
                ),
                "https://dontkillmyapp.com/realme",
            )

            KnownOem.GENERIC -> null
        }

    // ── Intent launchers ─────────────────────────────────────────────────────

    /**
     * Tries each OEM intent in priority order. Returns true if one succeeds.
     *
     * Strategy: first attempt [Intent.resolveActivity]; if that returns null (common on OEMs
     * that don't export the activity properly) try starting anyway — some OEMs block
     * resolution but still handle the implicit start. Any [Exception] is swallowed and the
     * next intent in the list is tried.
     */
    fun launchOemSettings(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                val flagged = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (flagged.resolveActivity(context.packageManager) != null) {
                    context.startActivity(flagged)
                    return true
                }
                context.startActivity(flagged)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "OEM intent failed (${intent.component?.className ?: intent.`package`}): ${e.message}")
            }
        }
        return false
    }

    /**
     * Opens the direct per-app battery-exemption prompt introduced in Android 6.0
     * (Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). Falls back to the
     * global battery-optimization list if the direct prompt fails.
     *
     * Requires <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
     * in the manifest.
     */
    fun launchDirectExemption(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            launchGeneralBatterySettings(context)
        }
    }

    /** Opens the system-wide battery-optimization list as a fallback. */
    fun launchGeneralBatterySettings(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Opens the dontkillmyapp.com page for visual guidance. */
    fun launchWebGuide(context: Context, url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {}
    }
}
