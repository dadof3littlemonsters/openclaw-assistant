package com.openclaw.assistant.ui.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    Scaffold(topBar = { TopAppBar(title = { Text("Mobile Bridge") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Enable Mobile Bridge", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = {
                    cfg.setEnabled(it)
                    if (it) {
                        cfg.getOrCreateToken()
                        MobileBridgeService.start(context)
                    } else {
                        MobileBridgeService.stop(context)
                    }
                })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Off by default. When enabled, an HTTP service runs on this device so Hermes (or other agents) can call a curated set of Android capabilities — every request requires the bridge token.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit); portText.toIntOrNull()?.let(cfg::setPort) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Bind mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BridgeBindMode.values().forEach { mode ->
                    FilterChip(selected = bindMode == mode, onClick = { cfg.setBindMode(mode) }, label = { Text(modeLabel(mode)) })
                }
            }
            if (bindMode == BridgeBindMode.LAN) {
                Spacer(Modifier.height(4.dp))
                Text("⚠ LAN bind exposes this device on the network. Prefer Tailscale or adb forward.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Text("Approval mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BridgeApprovalMode.values().forEach { mode ->
                    FilterChip(selected = approvalMode == mode, onClick = { cfg.setApprovalMode(mode) }, label = { Text(approvalLabel(mode)) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Bridge token", style = MaterialTheme.typography.titleSmall)
                    val token = remember(rotated) { cfg.tokenOrNull() ?: "(none)" }
                    Text(
                        if (showToken) token else "•".repeat(token.length.coerceAtMost(32)),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Reveal") }
                        Button(onClick = { cfg.rotateToken(); rotated++; showToken = true }) { Text("Rotate") }
                        Button(onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("bridge token", cfg.tokenOrNull() ?: ""))
                        }) { Text("Copy") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Capability allowlist", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val groups = listOf("device", "apps", "clipboard.read", "medium")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    FilterChip(selected = g in allowedGroups, onClick = {
                        val next = if (g in allowedGroups) allowedGroups - g else allowedGroups + g
                        cfg.setAllowedCapabilityGroups(next)
                    }, label = { Text(g) })
                }
            }

            Spacer(Modifier.height(16.dp))
            AssistChip(onClick = {}, label = { Text("Device URL: http://<device-ip>:$port") })

            Spacer(Modifier.height(16.dp))
            Text("Pair a remote", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(android.content.Intent(context, com.openclaw.assistant.bridge.pairing.BridgePairingActivity::class.java))
                }) { Text("Open pairing screen") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Active grants", style = MaterialTheme.typography.labelLarge)
            val grants = com.openclaw.assistant.bridge.grants.BridgeGrants.snapshot()
            if (grants.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall)
            else grants.forEach { g ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(g.capability, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revoke(g.capability) }) { Text("Revoke") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revokeAll() }) { Text("Revoke all grants") }

            Spacer(Modifier.height(16.dp))
            Text("Accessibility Bridge", style = MaterialTheme.typography.labelLarge)
            val a11yEnabled = com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning()
            Text(
                if (a11yEnabled) "✓ Service is enabled" else "Off — enable in Settings → Accessibility",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open Accessibility settings") }

            if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) {
                Spacer(Modifier.height(4.dp))
                Text("This build is configured for Play distribution: Accessibility Bridge and SMS are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
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
