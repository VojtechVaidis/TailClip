package com.example.tailclip.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.example.tailclip.data.DeviceInfo
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
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
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
                text = "Multi-Device Clipboard Sync",
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
                        text = "SERVER",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.host,
                        onValueChange = viewModel::onHostChange,
                        label = { Text("Server IP") },
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

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.deviceName,
                        onValueChange = viewModel::onDeviceNameChange,
                        label = { Text("Device Name") },
                        placeholder = { Text("My Phone") },
                        singleLine = true,
                        enabled = !uiState.isServiceRunning,
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

            // Connected Devices card
            if (uiState.isServiceRunning) {
                ConnectedDevicesCard(
                    devices = uiState.connectedDevices,
                    sendToAll = uiState.sendToAll,
                    selectedDeviceIds = uiState.selectedDeviceIds,
                    onSendToAllToggle = viewModel::onSendToAllToggle,
                    onToggleDevice = viewModel::onToggleDevice,
                )

                Spacer(Modifier.height(24.dp))
            }

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

            Spacer(Modifier.height(24.dp))

            // Quick Settings hint
            Text(
                text = "📌 Add \"TailClip Send\" tile to Quick Settings\nto send clipboard to selected devices",
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

@Composable
private fun ConnectedDevicesCard(
    devices: List<DeviceInfo>,
    sendToAll: Boolean,
    selectedDeviceIds: Set<String>,
    onSendToAllToggle: (Boolean) -> Unit,
    onToggleDevice: (String) -> Unit,
) {
    val otherDevices = devices.filter { !it.isSelf }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "CONNECTED DEVICES",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.4f),
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(16.dp))

            // Send to All toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Send to All",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = sendToAll,
                    onCheckedChange = onSendToAllToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Teal600,
                    )
                )
            }

            if (otherDevices.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No other devices connected",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.4f),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))

                otherDevices.forEach { device ->
                    DeviceRow(
                        device = device,
                        isSelected = sendToAll || selectedDeviceIds.contains(device.deviceId),
                        enabled = !sendToAll,
                        onToggle = { onToggleDevice(device.deviceId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val emoji = when (device.deviceType) {
        "android" -> "📱"
        "desktop" -> "💻"
        "cli" -> "⌨️"
        else -> "🔗"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            fontSize = 20.sp,
            modifier = Modifier.width(28.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.deviceName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                text = device.deviceType,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = Teal400,
                checkmarkColor = Color.White,
            )
        )
    }
}

