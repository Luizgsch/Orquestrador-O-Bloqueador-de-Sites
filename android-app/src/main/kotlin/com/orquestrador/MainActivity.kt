package com.orquestrador

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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
import com.orquestrador.db.BlockListInitializer
import com.orquestrador.ui.OrquestradorScreen
import com.orquestrador.ui.theme.Obsidian
import com.orquestrador.ui.theme.OrquestradorTheme
import com.orquestrador.vpn.OrquestradorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: OrquestradorViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BlockListInitializer.initializeDatabase(this)
        setContent {
            OrquestradorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Obsidian,
                ) {
                    val state by viewModel.state.collectAsState()

                    LaunchedEffect(state.needsVpnPermission) {
                        if (state.needsVpnPermission) {
                            val intent = VpnService.prepare(this@MainActivity)
                            if (intent != null) vpnPermissionLauncher.launch(intent)
                            else viewModel.onVpnPermissionResult(true)
                        }
                    }

                    OrquestradorScreen(viewModel = viewModel, state = state)
                }
            }
        }
    }
}
