package com.orquestrador.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.orquestrador.ui.theme.AccentAdult
import com.orquestrador.ui.theme.AccentManga
import com.orquestrador.ui.theme.AccentSocial
import com.orquestrador.ui.theme.CardBorderActive
import com.orquestrador.ui.theme.CardBorderIdle
import com.orquestrador.ui.theme.CardSurface
import com.orquestrador.ui.theme.ElectricBlue
import com.orquestrador.ui.theme.Obsidian
import com.orquestrador.ui.theme.OrquestradorTheme
import com.orquestrador.ui.theme.StatusGreen
import com.orquestrador.ui.theme.StatusGreenDim
import com.orquestrador.ui.theme.StatusRed
import com.orquestrador.ui.theme.StatusRedDim
import com.orquestrador.ui.theme.SwitchTrackOff
import com.orquestrador.ui.theme.TextDim
import com.orquestrador.ui.theme.TextPrimary
import com.orquestrador.ui.theme.TextSecondary
import com.orquestrador.security.SecurityPreferences

// ─────────────────────────────────────────────
// Screen state model
// ─────────────────────────────────────────────

private data class BlockModule(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color,
    val enabled: Boolean,
    val category: com.orquestrador.vpn.BlockList.Category,
)

// ─────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────

@Composable
fun OrquestradorScreen(
    viewModel: com.orquestrador.vpn.OrquestradorViewModel,
    state: com.orquestrador.vpn.VpnUiState,
    onRequestOverlayPermission: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
) {
    val context = LocalContext.current
    val secPrefs = remember { SecurityPreferences(context) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun withPin(action: () -> Unit) {
        if (secPrefs.isPinSet()) pendingAction = action else action()
    }

    pendingAction?.let {
        PasswordEntryDialog(
            onConfirm = { pin ->
                val ok = secPrefs.verifyPin(pin)
                if (ok) { it(); pendingAction = null }
                ok
            },
            onDismiss = { pendingAction = null },
        )
    }

    val modules = listOf(
        BlockModule(
            "Redes Sociais", "Instagram, TikTok, X, YouTube",
            Icons.Outlined.Groups, AccentSocial, state.socialEnabled,
            com.orquestrador.vpn.BlockList.Category.SOCIAL
        ),
        BlockModule(
            "Conteúdo Adulto", "Pornografia e sites adultos",
            Icons.Outlined.Block, AccentAdult, state.adultEnabled,
            com.orquestrador.vpn.BlockList.Category.ADULT
        ),
        BlockModule(
            "Mangás / Distrações", "MangaDex, Webtoons e similares",
            Icons.Outlined.MenuBook, AccentManga, state.mangaEnabled,
            com.orquestrador.vpn.BlockList.Category.MANGA
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .drawBehind { drawTacticalGrid() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 60.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AppHeader()

            Spacer(Modifier.height(2.dp))

            ProtectionCard(
                isActive = state.isVpnRunning,
                isVpnConnected = state.isVpnRunning,
                onToggleProtection = { enabled -> withPin { viewModel.onVpnToggle(enabled) } },
                onToggleVpn = { enabled -> withPin { viewModel.onVpnToggle(enabled) } },
            )

            if (state.needsOverlayPermission) {
                OverlayPermissionBanner(onGrant = onRequestOverlayPermission)
            }

            SectionHeader(title = "MÓDULOS DE BLOQUEIO")

            modules.forEach { module ->
                ModuleCard(
                    module = module,
                    onToggle = { enabled ->
                        withPin { viewModel.onCategoryToggle(module.category, enabled) }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            SecurityNavButton(onClick = onNavigateToSecurity)
        }
    }
}

// ─────────────────────────────────────────────
// Background
// ─────────────────────────────────────────────

private fun DrawScope.drawTacticalGrid() {
    val step = 44.dp.toPx()
    val lineColor = Color(0xFF1E2B44).copy(alpha = 0.22f)
    var x = 0f
    while (x <= size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
        y += step
    }
    // Corner accent: faint radial blue bloom top-left
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2563EB).copy(alpha = 0.06f), Color.Transparent),
            center = Offset(0f, 0f),
            radius = size.width * 0.7f,
        ),
        radius = size.width * 0.7f,
        center = Offset(0f, 0f),
    )
}

// ─────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────

@Composable
private fun AppHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF2563EB).copy(alpha = 0.25f), Color.Transparent),
                    ),
                    CircleShape,
                )
                .border(1.dp, ElectricBlue.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                tint = ElectricBlue,
                modifier = Modifier.size(20.dp),
            )
        }

        Column {
            Text(
                text = "ORQUESTRADOR",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary,
            )
            Text(
                text = "SISTEMA DE CONTROLE DE REDE",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
        }
    }
}

// ─────────────────────────────────────────────
// Protection card
// ─────────────────────────────────────────────

@Composable
private fun ProtectionCard(
    isActive: Boolean,
    isVpnConnected: Boolean,
    onToggleProtection: (Boolean) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) CardBorderActive else CardBorderIdle,
        animationSpec = tween(600),
        label = "card_border",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.18f else 0f,
        animationSpec = tween(700),
        label = "card_glow",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ElectricBlue.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.width * 0.75f,
                        ),
                        cornerRadius = CornerRadius(20.dp.toPx()),
                    )
                }
            }
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .background(CardSurface, RoundedCornerShape(20.dp))
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PROTEÇÃO ATIVA",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                StatusBadge(isActive = isActive, activeLabel = "ATIVO", inactiveLabel = "INATIVO")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (isActive) "Sistema protegido" else "Sistema desprotegido",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isActive) TextSecondary else StatusRed,
                    )
                    Text(
                        text = if (isActive) "Regras ativas em /etc/hosts" else "Nenhuma regra aplicada",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                }

                Switch(
                    checked = isActive,
                    onCheckedChange = onToggleProtection,
                    modifier = Modifier.scale(1.15f),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ElectricBlue,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = SwitchTrackOff,
                        uncheckedThumbColor = TextDim,
                    ),
                )
            }

            HorizontalDivider(color = CardBorderIdle.copy(alpha = 0.6f), thickness = 0.5.dp)

            VpnRow(isConnected = isVpnConnected, onToggle = onToggleVpn)
        }
    }
}

// ─────────────────────────────────────────────
// VPN row
// ─────────────────────────────────────────────

@Composable
private fun VpnRow(isConnected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val iconTint by animateColorAsState(
                targetValue = if (isConnected) StatusGreen else TextDim,
                animationSpec = tween(400),
                label = "vpn_icon_tint",
            )
            Icon(
                imageVector = if (isConnected) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "VPN LOCAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
                val statusColor by animateColorAsState(
                    targetValue = if (isConnected) StatusGreen else TextSecondary,
                    animationSpec = tween(400),
                    label = "vpn_text_color",
                )
                Text(
                    text = if (isConnected) "Conectada" else "Desconectada",
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                )
            }
        }

        Switch(
            checked = isConnected,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = StatusGreenDim,
                checkedThumbColor = StatusGreen,
                uncheckedTrackColor = SwitchTrackOff,
                uncheckedThumbColor = TextDim,
            ),
        )
    }
}

// ─────────────────────────────────────────────
// Pulsing status badge
// ─────────────────────────────────────────────

@Composable
private fun StatusBadge(isActive: Boolean, activeLabel: String, inactiveLabel: String) {
    val bg by animateColorAsState(
        targetValue = if (isActive) StatusGreenDim else StatusRedDim,
        animationSpec = tween(500),
        label = "badge_bg",
    )
    val dot by animateColorAsState(
        targetValue = if (isActive) StatusGreen else StatusRed,
        animationSpec = tween(500),
        label = "badge_dot",
    )
    val label by animateColorAsState(
        targetValue = if (isActive) StatusGreen else StatusRed,
        animationSpec = tween(500),
        label = "badge_label",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dot.copy(alpha = if (isActive) pulse else 1f), CircleShape),
        )
        Text(
            text = if (isActive) activeLabel else inactiveLabel,
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
    }
}

// ─────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = CardBorderIdle.copy(alpha = 0.5f),
            thickness = 0.5.dp,
        )
    }
}

// ─────────────────────────────────────────────
// Module card
// ─────────────────────────────────────────────

@Composable
private fun ModuleCard(module: BlockModule, onToggle: (Boolean) -> Unit) {
    val accentAlpha by animateFloatAsState(
        targetValue = if (module.enabled) 1f else 0.35f,
        animationSpec = tween(400),
        label = "module_accent_alpha",
    )
    val borderColor by animateColorAsState(
        targetValue = if (module.enabled) module.accent.copy(alpha = 0.45f) else CardBorderIdle,
        animationSpec = tween(400),
        label = "module_border",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
            .background(CardSurface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    module.accent.copy(alpha = 0.08f + 0.10f * accentAlpha),
                    RoundedCornerShape(11.dp),
                )
                .border(
                    0.5.dp,
                    module.accent.copy(alpha = 0.25f * accentAlpha),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = module.icon,
                contentDescription = null,
                tint = module.accent.copy(alpha = accentAlpha),
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = module.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (module.enabled) TextPrimary else TextSecondary,
            )
            Text(
                text = module.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextDim,
            )
        }

        Switch(
            checked = module.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = module.accent.copy(alpha = 0.65f),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = SwitchTrackOff,
                uncheckedThumbColor = TextDim,
            ),
        )
    }
}

// ─────────────────────────────────────────────
// Overlay permission banner
// ─────────────────────────────────────────────

@Composable
private fun OverlayPermissionBanner(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StatusRedDim, RoundedCornerShape(10.dp))
            .border(0.5.dp, StatusRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PROTEÇÃO ATIVA",
                style = MaterialTheme.typography.labelSmall,
                color = StatusRed,
            )
            Text(
                text = "Conceda permissão de sobreposição para ativar o Modo Implacável.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        TextButton(onClick = onGrant) {
            Text(text = "CONCEDER", color = StatusRed, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────────────────────────
// Security nav button
// ─────────────────────────────────────────────

@Composable
private fun SecurityNavButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, CardBorderIdle, RoundedCornerShape(12.dp))
            .background(CardSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Segurança",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
            )
        }
        TextButton(onClick = onClick) {
            Text("Configurar PIN", color = AccentSocial, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────

// Preview disabled — requires ViewModel context at runtime
// @Preview(showBackground = true, backgroundColor = 0xFF070A10, widthDp = 390, heightDp = 844)
// @Composable
// fun OrquestradorScreenPreview() { ... }
