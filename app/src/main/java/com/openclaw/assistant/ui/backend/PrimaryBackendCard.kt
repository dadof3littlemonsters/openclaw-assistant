package com.openclaw.assistant.ui.backend

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendManager
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home card that surfaces the current Primary backend, its connection state,
 * and quick actions to test the connection, switch primary, or open the
 * backend management screens. Shown above the existing OpenClaw home UI so it
 * never displaces the original layout.
 */
@Composable
fun PrimaryBackendCard() {
    val context = LocalContext.current
    val manager = remember { BackendManager.getInstance(context) }
    val backends by manager.backends.collectAsState()
    val primary = backends.firstOrNull { it.isPrimary && it.enabled }
    val others = backends.filter { !it.isPrimary && it.enabled }
    var status by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Primary backend", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(primary?.let { kindShort(it.type) } ?: "—") })
            }
            Spacer(Modifier.height(4.dp))
            Text(primary?.displayName ?: "No Primary configured", style = MaterialTheme.typography.bodyMedium)
            status?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (it.ok) "✓ ${it.message}" + (it.latencyMs?.let { l -> " (${l}ms)" } ?: "") else "✗ ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Also configured:", style = MaterialTheme.typography.labelMedium)
                others.forEach { Text("· ${it.displayName}", style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (primary == null) return@OutlinedButton
                    scope.launch {
                        status = ConnectionTestResult(false, "Testing…")
                        status = withContext(Dispatchers.IO) { AgentClientFactory.create(primary).testConnection() }
                    }
                }, enabled = primary != null) { Text("Test connection") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, BackendListActivity::class.java))
                }) { Text("Manage backends") }
            }
        }
    }
}

private fun kindShort(type: BackendType) = when (type) {
    BackendType.HERMES_API_SERVER -> "Hermes"
    BackendType.OPENCLAW_GATEWAY -> "Gateway"
    BackendType.OPENCLAW_HTTP -> "HTTP"
}
