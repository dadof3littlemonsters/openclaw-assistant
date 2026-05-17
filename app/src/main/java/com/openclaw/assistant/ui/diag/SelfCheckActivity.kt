package com.openclaw.assistant.ui.diag

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-check / diagnostic screen — the AgentVoice analogue of Hermes-Relay's
 * `hermes-status` command and `/hermes-relay-self-setup` skill. One scrollable
 * view that probes every load-bearing surface so a user (or Hermes itself
 * via the bridge) can answer "is everything wired correctly?" in a glance.
 *
 * Each check is a pure status read except for backend probes, which spawn
 * actual `testConnection()` calls and surface latency + winning endpoint.
 */
class SelfCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SelfCheckScreen() } }
    }
}

private data class CheckRow(val label: String, val ok: Boolean, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfCheckScreen() {
    val context = LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val cfg = remember { MobileBridgeConfig.getInstance(context) }

    var rows by remember { mutableStateOf(listOf<CheckRow>()) }
    val scope = rememberCoroutineScope()

    suspend fun runChecks() {
        val list = mutableListOf<CheckRow>()
        // Backend health
        val backends = repo.backends.value
        list += CheckRow(
            "Backends configured",
            backends.isNotEmpty(),
            if (backends.isEmpty()) "None — add one in Backends" else backends.joinToString(", ") { it.displayName },
        )
        val primary = backends.firstOrNull { it.isPrimary && it.enabled }
        list += CheckRow(
            "Primary backend",
            primary != null,
            primary?.displayName ?: "Not set",
        )
        primary?.let { p ->
            val r = withContext(Dispatchers.IO) { AgentClientFactory.create(p).testConnection() }
            list += CheckRow(
                "Primary reachable",
                r.ok,
                if (r.ok) "${r.message}${r.latencyMs?.let { " · ${it}ms" } ?: ""}" else r.message,
            )
        }

        // Bridge
        list += CheckRow("Mobile Bridge enabled", cfg.enabled.value, if (cfg.enabled.value) "On (port ${cfg.port.value})" else "Off")
        list += CheckRow("Bridge token present", cfg.tokenOrNull() != null, if (cfg.tokenOrNull() != null) "Stored encrypted" else "Generate from Mobile Bridge settings")

        // Permissions / opt-ins
        val notifAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        list += CheckRow("Notification access", notifAccess, if (notifAccess) "Granted" else "Open Settings → Notifications → Special access")
        val a11y = AgentVoiceAccessibilityService.isRunning()
        list += CheckRow("Accessibility Bridge", a11y, if (a11y) "Service running" else "Off — Settings → Accessibility")

        // Distribution gates
        list += CheckRow(
            "Build distribution",
            true,
            if (BuildConfig.IS_SIDELOAD) "Sideload (all capabilities available)" else "Play (Accessibility / SMS hidden)",
        )

        rows = list
    }

    LaunchedEffect(Unit) { runChecks() }

    Scaffold(topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_selfcheck_title)) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Health snapshot of AgentVoice. Tap “Re-run” after fixing anything below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            if (row.ok) "✓" else "✗",
                            color = if (row.ok) Color(0xFF34D399) else Color(0xFFEF4444),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(row.label, style = MaterialTheme.typography.titleSmall)
                            Text(row.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runChecks() } }) { Text("Re-run") }
            }
        }
    }
}
