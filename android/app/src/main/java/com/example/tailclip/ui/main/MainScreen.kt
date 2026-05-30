package com.example.tailclip.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tailclip.ws.ConnectionState

// Brand colors
private val Teal400 = Color(0xFF26A69A)
private val Teal600 = Color(0xFF00897B)
private val SurfaceDark = Color(0xFF1A1C1E)
private val CardDark = Color(0xFF2C2E32)
private val DotGreen = Color(0xFF4CAF50)
private val DotOrange = Color(0xFFFF9800)
private val DotRed = Color(0xFFF44336)

@Composable
fun MainScreen(
    viewModel: MainScreenViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceDark, Color(0xFF121316))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // Title
            Text(
                text = "TailClip",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Teal400
            )
            Text(
                text = "Clipboard Sync over Tailscale",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(40.dp))

            // Connection status card
            ConnectionStatusCard(uiState.connectionState)

            Spacer(Modifier.height(24.dp))

            // Server config card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Server",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.host,
                        onValueChange = viewModel::onHostChange,
                        label = { Text("Tailscale IP") },
                        placeholder = { Text("100.x.x.x") },
                        singleLine = true,
                        enabled = !uiState.isServiceRunning,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal400,
                            cursorColor = Teal400,
                            focusedLabelColor = Teal400
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.port,
                        onValueChange = viewModel::onPortChange,
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !uiState.isServiceRunning,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal400,
                            cursorColor = Teal400,
                            focusedLabelColor = Teal400
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Connect/Disconnect button
            Button(
                onClick = viewModel::toggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isServiceRunning) DotRed.copy(alpha = 0.8f) else Teal600
                ),
                enabled = uiState.host.isNotBlank()
            ) {
                Text(
                    text = if (uiState.isServiceRunning) "Disconnect" else "Connect",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(24.dp))

            // Last sync preview
            if (uiState.lastSyncPreview.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "LAST SYNCED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.4f),
                            letterSpacing = 1.5.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = uiState.lastSyncPreview,
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Quick Settings hint
            Text(
                text = "📌 Add \"TailClip Send\" tile to Quick Settings\nto send clipboard to PC",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.35f),
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: ConnectionState) {
    val dotColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> DotGreen
            ConnectionState.CONNECTING -> DotOrange
            ConnectionState.DISCONNECTED -> DotRed
        },
        animationSpec = tween(400),
        label = "dotColor"
    )

    val statusText = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.DISCONNECTED -> "Disconnected"
    }

    // Pulsing animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTING) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = pulseAlpha))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = statusText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
