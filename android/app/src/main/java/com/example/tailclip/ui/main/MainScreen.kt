package com.example.tailclip.ui.main

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tailclip.data.DeviceInfo
import com.example.tailclip.ws.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var selectedTab by remember { mutableStateOf(0) }

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
                .padding(horizontal = 24.dp)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

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

            Spacer(Modifier.height(24.dp))

            // Tab Selection
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Teal400,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Teal400
                    )
                },
                divider = {}
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("⚙️ Nastavení", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selectedTab == 0) Teal400 else Color.White.copy(alpha = 0.6f)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { 
                        selectedTab = 1
                        viewModel.refreshDownloadedFiles()
                    },
                    text = { Text("📂 Soubory", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selectedTab == 1) Teal400 else Color.White.copy(alpha = 0.6f)) }
                )
            }

            Spacer(Modifier.height(24.dp))

            if (selectedTab == 0) {
                // Settings tab (scrollable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Connection status card
                    ConnectionStatusCard(uiState.connectionState)

                    Spacer(Modifier.height(24.dp))

                    // Server config card
                    ServerConfigCard(uiState, viewModel)

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
                        Spacer(Modifier.height(24.dp))
                    }

                    // Quick Settings hint
                    Text(
                        text = "📌 Add \"TailClip Send\" tile to Quick Settings\nto send clipboard to selected devices",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            } else {
                // Files tab
                FilesTabContent(
                    files = uiState.downloadedFiles,
                    onRefresh = viewModel::refreshDownloadedFiles,
                    onOpenFile = viewModel::openFile,
                    onDeleteFile = viewModel::deleteFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ServerConfigCard(
    uiState: MainUiState,
    viewModel: MainScreenViewModel
) {
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
}

@Composable
private fun FilesTabContent(
    files: List<java.io.File>,
    onRefresh: () -> Unit,
    onOpenFile: (java.io.File) -> Unit,
    onDeleteFile: (java.io.File) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "📂",
                    fontSize = 64.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = "Žádné stažené soubory",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Soubory poslané z PC se zobrazí zde.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal600),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Aktualizovat")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(files) { file ->
                    FileRowItem(
                        file = file,
                        onClick = { onOpenFile(file) },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRowItem(
    file: java.io.File,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = file.extension.lowercase()
    val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon or image preview
            if (isImage) {
                LocalImagePreview(
                    file = file,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getFileEmoji(ext),
                        fontSize = 24.sp
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(file.length())} • ${formatFileDate(file.lastModified())}",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = "🗑️",
                    fontSize = 20.sp
                )
            }
        }
    }
}

@Composable
private fun LocalImagePreview(file: java.io.File, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                // Decode bitmap with downsampling to prevent OutOfMemory
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                
                // Downsample image to ~256px wide/high for previews
                val targetSize = 256
                var scale = 1
                while (options.outWidth / scale / 2 >= targetSize && options.outHeight / scale / 2 >= targetSize) {
                    scale *= 2
                }
                
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = scale
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                if (decoded != null) {
                    bitmap = decoded.asImageBitmap()
                }
            } catch (e: Exception) {
                android.util.Log.e("LocalImagePreview", "Failed to load image preview: ${e.message}")
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // Fallback placeholder while loading
        Box(
            modifier = modifier.background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Text("🖼️", fontSize = 24.sp)
        }
    }
}

private fun getFileEmoji(extension: String): String {
    return when (extension) {
        "pdf" -> "📕"
        "doc", "docx", "txt", "rtf", "odt" -> "📄"
        "xls", "xlsx", "csv" -> "📊"
        "ppt", "pptx" -> "📉"
        "zip", "rar", "tar", "gz", "7z" -> "📦"
        "mp3", "wav", "ogg", "flac", "m4a" -> "🎵"
        "mp4", "mkv", "avi", "mov", "webm" -> "🎥"
        "apk" -> "🤖"
        else -> "📄"
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

private fun formatFileDate(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    return format.format(date)
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

