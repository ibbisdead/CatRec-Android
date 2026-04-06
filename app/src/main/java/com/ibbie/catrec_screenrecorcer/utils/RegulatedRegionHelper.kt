package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Detects regions where privacy law requires explicit consent before analytics / personalized ads.
 * Uses SIM country, then network country, then device locale (first match wins).
 *
 * Covers EEA (EU + IS, LI, NO), United Kingdom, Switzerland (FADP), and Brazil (LGPD).
 * Extend [REGULATED_COUNTRY_CODES] if you add stricter territories.
 */
object RegulatedRegionHelper {

    private val REGULATED_COUNTRY_CODES = setOf(
        // EU member states
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
        "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA (non-EU)
        "IS", "LI", "NO",
        // United Kingdom
        "GB",
        // Switzerland
        "CH",
        // Brazil (LGPD)
        "BR",
    )

    fun isRegulatedPrivacyRegion(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val candidates = listOfNotNull(
            tm?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
            tm?.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
            Locale.getDefault().country.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        )
        return candidates.any { it in REGULATED_COUNTRY_CODES }
    }
}
