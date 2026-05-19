package com.openclaw.assistant.ui.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.bridge.BridgeApprovalMode
import com.openclaw.assistant.bridge.BridgeBindMode
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.bridge.MobileBridgeService

class MobileBridgeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MobileBridgeSettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBridgeSettingsScreen() {
    val context = LocalContext.current
    val cfg = remember { MobileBridgeConfig.getInstance(context) }
    val enabled by cfg.enabled.collectAsState()
    val port by cfg.port.collectAsState()
    val bindMode by cfg.bindMode.collectAsState()
    val approvalMode by cfg.approvalMode.collectAsState()
    val allowedGroups by cfg.allowedCapabilityGroups.collectAsState()
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var showToken by remember { mutableStateOf(false) }
    var rotated by remember { mutableStateOf(0) }
    val token = remember(rotated, enabled) { cfg.tokenOrNull() ?: "" }
    val a11yEnabled = com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning()

    Scaffold(topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.mobile_bridge_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BridgeStatusCard(
                enabled = enabled,
                port = port,
                bindMode = bindMode,
                tokenPresent = token.isNotBlank(),
                onEnabledChange = {
                    cfg.setEnabled(it)
                    if (it) {
                        cfg.getOrCreateToken()
                        rotated++
                        MobileBridgeService.start(context)
                    } else {
                        MobileBridgeService.stop(context)
                    }
                },
            )

            SettingsCard(title = "Connection", icon = Icons.Default.Link) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter(Char::isDigit)
                        portText.toIntOrNull()?.let(cfg::setPort)
                    },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text("Bind mode", style = MaterialTheme.typography.labelLarge)
                FlowChipRow {
                    BridgeBindMode.values().forEach { mode ->
                        FilterChip(
                            selected = bindMode == mode,
                            onClick = { cfg.setBindMode(mode) },
                            label = { Text(modeLabel(mode)) },
                        )
                    }
                }
                if (bindMode == BridgeBindMode.LAN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LAN / VPN exposes this device to the network. Use it only on trusted Wi-Fi or Tailscale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("http://<device-ip>:$port") })
            }

            SettingsCard(title = "Security", icon = Icons.Default.Security) {
                Text("Approval mode", style = MaterialTheme.typography.labelLarge)
                FlowChipRow {
                    BridgeApprovalMode.values().forEach { mode ->
                        FilterChip(
                            selected = approvalMode == mode,
                            onClick = { cfg.setApprovalMode(mode) },
                            label = { Text(approvalLabel(mode)) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bridge token", style = MaterialTheme.typography.labelLarge)
                        Text(
                            when {
                                token.isBlank() -> "Not generated yet"
                                showToken -> token
                                else -> "•".repeat(token.length.coerceAtMost(32))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { showToken = !showToken }, enabled = token.isNotBlank()) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                    }
                    IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("bridge token", cfg.tokenOrNull() ?: ""))
                    }, enabled = token.isNotBlank()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { cfg.rotateToken(); rotated++; showToken = true }) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rotate token")
                }
            }

            SettingsCard(title = "Capabilities", icon = Icons.Default.Devices) {
                Text(
                    "These are separate from the Home screen toggles. Only checked groups are exposed through the local Bridge HTTP API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowChipRow {
                    listOf("device", "apps", "clipboard.read", "medium").forEach { group ->
                        FilterChip(
                            selected = group in allowedGroups,
                            onClick = {
                                val next = if (group in allowedGroups) allowedGroups - group else allowedGroups + group
                                cfg.setAllowedCapabilityGroups(next)
                            },
                            label = { Text(group) },
                        )
                    }
                }
            }

            SettingsCard(title = "Accessibility Bridge", icon = Icons.Default.SettingsAccessibility) {
                StatusLine(
                    label = if (a11yEnabled) "Service enabled" else "Enable in Android Accessibility settings",
                    active = a11yEnabled,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open Accessibility settings") }
            }

            SettingsCard(title = "Pairing and checks", icon = Icons.Default.CheckCircle) {
                Text(
                    "Use the pairing screen for Hermes tools or another trusted client. OpenClaw inside this app uses its own Gateway/device-control path and does not need this token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(android.content.Intent(context, com.openclaw.assistant.bridge.pairing.BridgePairingActivity::class.java))
                    }) { Text("Pair a remote") }
                    OutlinedButton(onClick = {
                        context.startActivity(android.content.Intent(context, com.openclaw.assistant.ui.diag.SelfCheckActivity::class.java))
                    }) { Text("Self-check") }
                }
                val grants = com.openclaw.assistant.bridge.grants.BridgeGrants.snapshot()
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text("Active grants", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                if (grants.isEmpty()) {
                    Text("None", style = MaterialTheme.typography.bodySmall)
                } else {
                    grants.forEach { grant ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(grant.capability, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revoke(grant.capability) }) {
                                Text("Revoke")
                            }
                        }
                    }
                    OutlinedButton(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revokeAll() }) {
                        Text("Revoke all")
                    }
                }
            }

            if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) {
                Text(
                    "This build is configured for Play distribution: Accessibility Bridge and SMS are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BridgeStatusCard(
    enabled: Boolean,
    port: Int,
    bindMode: BridgeBindMode,
    tokenPresent: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusDot(active = enabled)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mobile Bridge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "Running on port $port (${modeLabel(bindMode)})" else "Off. Enable only when a trusted agent needs device access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (tokenPresent) "Token ready" else "No token") })
                AssistChip(onClick = {}, label = { Text(if (enabled) "Bridge API active" else "Bridge API stopped") })
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun StatusLine(label: String, active: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        StatusDot(active = active)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .then(
                Modifier
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Card(
            modifier = Modifier.size(12.dp),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            ),
        ) {}
    }
}

private fun modeLabel(m: BridgeBindMode) = when (m) {
    BridgeBindMode.LOCAL_ONLY -> "Local only"
    BridgeBindMode.LAN -> "LAN / VPN"
}
private fun approvalLabel(m: BridgeApprovalMode) = when (m) {
    BridgeApprovalMode.ALWAYS_CONFIRM -> "Always confirm"
    BridgeApprovalMode.CONFIRM_MEDIUM_HIGH -> "Confirm medium/high"
    BridgeApprovalMode.TRUSTED -> "Trusted (no prompts)"
}
