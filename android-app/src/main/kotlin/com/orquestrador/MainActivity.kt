package com.orquestrador

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.orquestrador.db.BlockListInitializer
import com.orquestrador.ui.OrquestradorScreen
import com.orquestrador.ui.SecurityScreen
import com.orquestrador.ui.theme.Obsidian
import com.orquestrador.ui.theme.OrquestradorTheme
import com.orquestrador.vpn.OrquestradorViewModel
import com.orquestrador.vpn.VpnGuardWorker

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_REACTIVATE_VPN = "com.orquestrador.ACTION_REACTIVATE_VPN"
    }

    private val viewModel: OrquestradorViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onOverlayPermissionStatus(Settings.canDrawOverlays(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BlockListInitializer.initializeDatabase(this)
        VpnGuardWorker.schedule(this)
        viewModel.onOverlayPermissionStatus(Settings.canDrawOverlays(this))
        handleIntent(intent)
        setContent {
            OrquestradorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Obsidian,
                ) {
                    val state by viewModel.state.collectAsState()
                    var showSecurity by remember { mutableStateOf(false) }

                    if (showSecurity) {
                        SecurityScreen(onBack = { showSecurity = false })
                        return@Surface
                    }

                    LaunchedEffect(state.needsVpnPermission) {
                        if (state.needsVpnPermission) {
                            val intent = VpnService.prepare(this@MainActivity)
                            if (intent != null) vpnPermissionLauncher.launch(intent)
                            else viewModel.onVpnPermissionResult(true)
                        }
                    }

                    LaunchedEffect(state.needsOverlayPermission) {
                        if (state.needsOverlayPermission) {
                            overlayPermissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                            viewModel.onOverlayPermissionRequested()
                        }
                    }

                    OrquestradorScreen(
                        viewModel = viewModel,
                        state = state,
                        onRequestOverlayPermission = {
                            viewModel.onOverlayPermissionStatus(false)
                        },
                        onNavigateToSecurity = { showSecurity = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_REACTIVATE_VPN) {
            viewModel.onVpnToggle(true)
        }
    }
}
