package com.community.dnsfix.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isConnected: Boolean,
    currentMode: ProtectionMode,
    pingMs: Int?, // Changed to Int? to natively represent the loading/error states
    onModeChange: (ProtectionMode) -> Unit,
    onToggleConnection: (Boolean) -> Unit
) {
    val activeColor = if (currentMode == ProtectionMode.BPS) {
        MaterialTheme.colorScheme.tertiary 
    } else {
        MaterialTheme.colorScheme.primary
    }

    val buttonColor by animateColorAsState(
        targetValue = if (isConnected) activeColor else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 400),
        label = "ButtonColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Core Network Engine", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            
            // Operational Mode Selector
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "SELECT ENGINE MODE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { if (!isConnected) onModeChange(ProtectionMode.BPS) },
                        selected = currentMode == ProtectionMode.BPS,
                        enabled = !isConnected
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BPS Mode")
                        }
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { if (!isConnected) onModeChange(ProtectionMode.VPN) },
                        selected = currentMode == ProtectionMode.VPN,
                        enabled = !isConnected
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("VPN Tunnel")
                        }
                    }
                }
            }

            // Status Card Descriptor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isConnected) activeColor else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = if (isConnected) "ENGINE ACTIVE" else "ENGINE DISCONNECTED",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isConnected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                !isConnected -> "Select a configuration mode and launch"
                                currentMode == ProtectionMode.BPS -> "Running standalone application background bypass routines"
                                else -> "Global layer-3 system tunnel active"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Interactive One-Tap Action Switch
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.12f))
                    .padding(16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(buttonColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current
                        ) { onToggleConnection(!isConnected) }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power Action",
                            tint = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isConnected) "STOP" else "START",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Real-Time Diagnostic Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // Dynamic calculation states
                        val hasValidPing = isConnected && pingMs != null && pingMs > 0
                        
                        val latencyText = when {
                            !isConnected -> "--"
                            pingMs == null || pingMs == 0 -> "Checking..."
                            else -> "$pingMs ms"
                        }
                        
                        val latencyColor = when {
                            !hasValidPing -> MaterialTheme.colorScheme.onSurfaceVariant
                            pingMs!! < 80 -> Color(0xFF2E7D32)  // Great connection (Green)
                            pingMs > 200 -> Color(0xFFC62828)   // Heavy delay (Red)
                            else -> Color(0xFFEF6C00)           // Moderate delay (Orange)
                        }

                        Text(
                            text = latencyText,
                            style = if (!hasValidPing) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = latencyColor
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Scope", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isConnected) currentMode.name else "None",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
