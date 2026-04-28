package com.ibbie.catrec_screenrecorcer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SettingsPower
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ui.components.GlassCard
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.utils.BatteryOptimizationHelper

/**
 * Full rationale dialog for battery-optimization exemption.
 *
 * Shows:
 *  - Explanation of why unrestricted battery access is needed.
 *  - Primary CTA: direct per-app exemption prompt (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
 *  - OEM section (only on aggressive-killer devices): opens manufacturer-specific settings.
 *  - Web guide link: opens https://dontkillmyapp.com/<manufacturer> in the browser.
 *  - "Not Now" dismiss button.
 *
 * [onDismiss] is called whenever the user dismisses the dialog (either via "Not Now",
 * the system back gesture, or after granting the permission).
 */
@Composable
fun BatteryOptimizationRationaleDialog(
    onDismiss: () -> Unit,
    viewModel: BatteryOptimizationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val accent = LocalAccentColor.current

    // Refresh exemption status every time the user comes back from Settings.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    if (uiState.isExempted) {
        onDismiss()
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // ── Header ────────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.battery_opt_rationale_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── Rationale text ────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.battery_opt_rationale_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── OEM-specific section ──────────────────────────────────────
                uiState.oemInfo?.let { oem ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = accent.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.battery_opt_oem_note, oem.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Primary CTA: direct exemption ─────────────────────────────
                Button(
                    onClick = {
                        BatteryOptimizationHelper.launchDirectExemption(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsPower,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.battery_opt_btn_grant),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // ── OEM settings button (aggressive killers only) ─────────────
                uiState.oemInfo?.let { oem ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val launched = BatteryOptimizationHelper.launchOemSettings(context, oem.settingsIntents)
                            if (!launched) BatteryOptimizationHelper.launchGeneralBatterySettings(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsPower,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.battery_opt_btn_oem_settings, oem.displayName))
                    }

                    // ── Web guide link ────────────────────────────────────────
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { BatteryOptimizationHelper.launchWebGuide(context, oem.webGuideUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.battery_opt_btn_learn_more),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ── Dismiss ───────────────────────────────────────────────────
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(R.string.battery_opt_btn_not_now),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Compact inline banner shown in the main recording flow.
 * Appears only when the device is an aggressive-killer OEM and battery is not yet exempted.
 * Tapping "Fix" opens the rationale dialog; "✕" dismisses the banner for the session.
 */
@Composable
fun BatteryOptimizationBanner(
    onFixClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    Surface(
        color = accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.battery_opt_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onFixClick,
                colors = ButtonDefaults.textButtonColors(contentColor = accent),
            ) {
                Text(
                    text = stringResource(R.string.battery_opt_banner_fix),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text("✕", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Small status chip shown in SettingsScreen when the app is already exempted.
 */
@Composable
fun BatteryOptimizationStatusChip(modifier: Modifier = Modifier) {
    val accent = Color(0xFF4CAF50)
    Surface(
        color = accent.copy(alpha = 0.15f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.battery_opt_status_exempted),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
