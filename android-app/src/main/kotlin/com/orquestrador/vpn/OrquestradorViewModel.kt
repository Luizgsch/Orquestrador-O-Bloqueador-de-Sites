package com.orquestrador.vpn

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnUiState(
    val isVpnRunning: Boolean = false,
    val socialEnabled: Boolean = true,
    val adultEnabled: Boolean = true,
    val mangaEnabled: Boolean = false,
    val needsVpnPermission: Boolean = false,
    val needsOverlayPermission: Boolean = false,
)

class OrquestradorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    init {
        val prefs = VpnPreferences(app)
        val savedCats = prefs.getEnabledCategories()
        if (savedCats.isNotEmpty()) {
            _state.value = _state.value.copy(
                isVpnRunning = OrquestradorVpnService.isRunning,
                socialEnabled = BlockList.Category.SOCIAL.name in savedCats,
                adultEnabled = BlockList.Category.ADULT.name in savedCats,
                mangaEnabled = BlockList.Category.MANGA.name in savedCats,
            )
        } else {
            _state.value = _state.value.copy(isVpnRunning = OrquestradorVpnService.isRunning)
        }
    }

    fun onVpnToggle(enabled: Boolean) {
        if (enabled) {
            val permIntent = VpnService.prepare(getApplication())
            if (permIntent != null) {
                _state.value = _state.value.copy(needsVpnPermission = true)
            } else {
                startVpnService()
            }
        } else {
            stopVpnService()
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(needsVpnPermission = false)
        if (granted) startVpnService()
    }

    fun onOverlayPermissionStatus(granted: Boolean) {
        _state.value = _state.value.copy(needsOverlayPermission = !granted)
    }

    fun onOverlayPermissionRequested() {
        _state.value = _state.value.copy(needsOverlayPermission = false)
    }

    fun onCategoryToggle(category: BlockList.Category, enabled: Boolean) {
        _state.value = when (category) {
            BlockList.Category.SOCIAL -> _state.value.copy(socialEnabled = enabled)
            BlockList.Category.ADULT -> _state.value.copy(adultEnabled = enabled)
            BlockList.Category.MANGA -> _state.value.copy(mangaEnabled = enabled)
        }
        if (_state.value.isVpnRunning) updateVpnCategories()
    }

    private fun startVpnService() {
        val intent = buildServiceIntent(OrquestradorVpnService.ACTION_START)
        getApplication<Application>().startService(intent)
        _state.value = _state.value.copy(isVpnRunning = true)
    }

    private fun stopVpnService() {
        val intent = Intent(getApplication(), OrquestradorVpnService::class.java)
            .setAction(OrquestradorVpnService.ACTION_STOP)
        getApplication<Application>().startService(intent)
        _state.value = _state.value.copy(isVpnRunning = false)
    }

    private fun updateVpnCategories() {
        val intent = buildServiceIntent(OrquestradorVpnService.ACTION_START)
        getApplication<Application>().startService(intent)
    }

    private fun buildServiceIntent(action: String): Intent {
        val cats = buildList {
            if (_state.value.socialEnabled) add(BlockList.Category.SOCIAL.name)
            if (_state.value.adultEnabled) add(BlockList.Category.ADULT.name)
            if (_state.value.mangaEnabled) add(BlockList.Category.MANGA.name)
        }.toTypedArray()
        return Intent(getApplication(), OrquestradorVpnService::class.java)
            .setAction(action)
            .putExtra(OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES, cats)
    }
}
