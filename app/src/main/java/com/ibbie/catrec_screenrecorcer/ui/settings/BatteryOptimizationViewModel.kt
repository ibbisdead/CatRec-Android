package com.ibbie.catrec_screenrecorcer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ibbie.catrec_screenrecorcer.utils.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryOptimizationViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val isExempted: Boolean,
        val oemInfo: BatteryOptimizationHelper.OemKillerInfo?,
    )

    private val _uiState = MutableStateFlow(buildState())
    val uiState = _uiState.asStateFlow()

    /** Call after returning from any Settings screen so the exemption status is fresh. */
    fun refresh() {
        _uiState.value = buildState()
    }

    private fun buildState(): UiState {
        val app = getApplication<Application>()
        return UiState(
            isExempted = BatteryOptimizationHelper.isExempted(app),
            oemInfo = BatteryOptimizationHelper.getOemKillerInfo(),
        )
    }
}
