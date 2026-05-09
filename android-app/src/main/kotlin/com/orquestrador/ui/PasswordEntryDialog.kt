package com.orquestrador.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orquestrador.ui.theme.AccentSocial
import com.orquestrador.ui.theme.CardSurface
import com.orquestrador.ui.theme.Obsidian
import com.orquestrador.ui.theme.StatusRed
import com.orquestrador.ui.theme.TextDim
import com.orquestrador.ui.theme.TextPrimary
import com.orquestrador.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PasswordEntryDialog(
    title: String = "PIN de Segurança",
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PinDots(pin = pin)

                Spacer(Modifier.height(16.dp))

                TextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) {
                            pin = value
                            error = false
                        }
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    singleLine = true,
                )

                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "PIN incorreto",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusRed,
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier.graphicsLayer { translationX = shakeOffset.value }
            ) {
                Button(
                    onClick = {
                        val ok = onConfirm(pin)
                        if (!ok) {
                            error = true
                            pin = ""
                            scope.launch {
                                shakeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = keyframes {
                                        durationMillis = 300
                                        -12f at 50
                                        12f at 100
                                        -8f at 150
                                        8f at 200
                                        -4f at 250
                                        0f at 300
                                    },
                                )
                            }
                        }
                    },
                    enabled = pin.length == 4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentSocial,
                        disabledContainerColor = TextDim,
                    ),
                ) {
                    Text("Confirmar", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun PinDots(pin: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { index ->
            val filled = index < pin.length
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = if (filled) AccentSocial else Color.Transparent,
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (filled) AccentSocial else TextDim,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
