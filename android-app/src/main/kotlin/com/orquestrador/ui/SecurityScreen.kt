package com.orquestrador.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.orquestrador.security.SecurityPreferences
import com.orquestrador.ui.theme.AccentSocial
import com.orquestrador.ui.theme.CardBorderIdle
import com.orquestrador.ui.theme.CardSurface
import com.orquestrador.ui.theme.Obsidian
import com.orquestrador.ui.theme.StatusGreen
import com.orquestrador.ui.theme.StatusRed
import com.orquestrador.ui.theme.TextDim
import com.orquestrador.ui.theme.TextPrimary
import com.orquestrador.ui.theme.TextSecondary

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val secPrefs = remember { SecurityPreferences(context) }
    val pinSet = remember { mutableStateOf(secPrefs.isPinSet()) }

    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<Feedback?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Voltar",
                    tint = TextSecondary,
                )
            }
            Text(
                text = "Segurança",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = AccentSocial,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    text = if (pinSet.value) "Alterar PIN" else "Definir PIN",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }

            Text(
                text = if (pinSet.value)
                    "Insira o PIN atual e depois o novo PIN de 4 dígitos."
                else
                    "Defina um PIN de 4 dígitos para proteger os controles do Orquestrador.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            if (pinSet.value) {
                PinField(
                    label = "PIN atual",
                    value = currentPin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) currentPin = it },
                )
            }

            PinField(
                label = "Novo PIN",
                value = newPin,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) newPin = it },
            )

            PinField(
                label = "Confirmar novo PIN",
                value = confirmPin,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) confirmPin = it },
            )

            feedback?.let { fb ->
                Text(
                    text = fb.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fb.isError) StatusRed else StatusGreen,
                )
            }

            Button(
                onClick = {
                    feedback = validate(
                        secPrefs = secPrefs,
                        pinIsSet = pinSet.value,
                        currentPin = currentPin,
                        newPin = newPin,
                        confirmPin = confirmPin,
                    )
                    if (feedback?.isError == false) {
                        pinSet.value = true
                        currentPin = ""
                        newPin = ""
                        confirmPin = ""
                    }
                },
                enabled = if (pinSet.value) currentPin.length == 4 && newPin.length == 4 && confirmPin.length == 4
                          else newPin.length == 4 && confirmPin.length == 4,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentSocial,
                    disabledContainerColor = TextDim,
                ),
            ) {
                Text(if (pinSet.value) "Alterar PIN" else "Salvar PIN", color = Color.White)
            }
        }
    }
}

@Composable
private fun PinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextDim) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentSocial,
            unfocusedBorderColor = CardBorderIdle,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentSocial,
        ),
    )
}

private data class Feedback(val message: String, val isError: Boolean)

private fun validate(
    secPrefs: SecurityPreferences,
    pinIsSet: Boolean,
    currentPin: String,
    newPin: String,
    confirmPin: String,
): Feedback {
    if (pinIsSet && !secPrefs.verifyPin(currentPin)) {
        return Feedback("PIN atual incorreto", isError = true)
    }
    if (newPin != confirmPin) {
        return Feedback("PINs não coincidem", isError = true)
    }
    secPrefs.savePin(newPin)
    return Feedback("PIN salvo com sucesso", isError = false)
}
