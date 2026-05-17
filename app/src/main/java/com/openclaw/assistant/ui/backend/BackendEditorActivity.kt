package com.openclaw.assistant.ui.backend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackendEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_BACKEND_ID)
        setContent {
            MaterialTheme {
                BackendEditorScreen(existingId = id, onDone = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_BACKEND_ID = "backendId"
        fun intent(context: Context, id: String?): Intent =
            Intent(context, BackendEditorActivity::class.java).apply { id?.let { putExtra(EXTRA_BACKEND_ID, it) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendEditorScreen(existingId: String?, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val backends by repo.backends.collectAsState()
    val existing = remember(existingId, backends) { backends.firstOrNull { it.id == existingId } }

    var type by remember { mutableStateOf(existing?.type ?: BackendType.HERMES_API_SERVER) }
    var displayName by remember { mutableStateOf(existing?.displayName ?: defaultName(type)) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var token by remember { mutableStateOf(existing?.apiKeyOrToken.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString().orEmpty()) }
    var useTls by remember { mutableStateOf(existing?.useTls ?: true) }
    var modelName by remember { mutableStateOf(existing?.modelName ?: "hermes-agent") }
    var useRunsApi by remember { mutableStateOf(existing?.useRunsApi ?: false) }
    var useStreaming by remember { mutableStateOf(existing?.useStreaming ?: true) }
    var setPrimary by remember { mutableStateOf(existing?.isPrimary ?: backends.isEmpty()) }
    var lanUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(0).orEmpty()) }
    var tailscaleUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(1).orEmpty()) }
    var publicUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(2).orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "Add backend" else "Edit backend") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendType.values().forEach { t ->
                    FilterChip(selected = type == t, onClick = {
                        type = t
                        if (displayName.isBlank() || displayName == defaultName(t)) displayName = defaultName(t)
                    }, label = { Text(shortLabel(t)) })
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            when (type) {
                BackendType.HERMES_API_SERVER -> {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Primary URL (e.g. http://host:8642)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Additional endpoints — raced in parallel on every connect, fastest wins (Hermes-Relay style):", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = lanUrl, onValueChange = { lanUrl = it }, label = { Text("LAN URL (optional)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = tailscaleUrl, onValueChange = { tailscaleUrl = it }, label = { Text("Tailscale URL (optional)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = publicUrl, onValueChange = { publicUrl = it }, label = { Text("Public URL (optional)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text("Model (default hermes-agent)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useRunsApi, onCheckedChange = { useRunsApi = it }); Text("Use Runs API (otherwise /v1/chat/completions)")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useStreaming, onCheckedChange = { useStreaming = it }); Text("Stream responses")
                    }
                }
                BackendType.OPENCLAW_GATEWAY -> {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Gateway token") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useTls, onCheckedChange = { useTls = it }); Text("Use TLS")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "For pairing, QR-code setup, or discovery, use the OpenClaw setup screen on the Home tab — it handles certificate fingerprints and TLS trust prompts. Save this entry once host/port/token are confirmed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BackendType.OPENCLAW_HTTP -> {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Auth token (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = setPrimary, onCheckedChange = { setPrimary = it }); Text("Mark as Primary")
            }

            Spacer(Modifier.height(16.dp))
            val secondary = listOf(lanUrl, tailscaleUrl, publicUrl).filter { it.isNotBlank() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, secondary)
                    repo.upsert(config)
                    if (setPrimary) repo.setPrimary(config.id)
                    onDone()
                }) { Text("Save") }

                Button(onClick = {
                    val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, secondary)
                    scope.launch {
                        status = "Testing…"
                        val r = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                        status = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"
                    }
                }) { Text("Test") }
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun buildConfig(
    existing: AgentBackendConfig?,
    type: BackendType,
    displayName: String,
    baseUrl: String,
    token: String,
    host: String,
    port: String,
    useTls: Boolean,
    modelName: String,
    useRunsApi: Boolean,
    useStreaming: Boolean,
    isPrimary: Boolean,
    secondaryUrls: List<String> = emptyList(),
): AgentBackendConfig {
    val base = existing ?: AgentBackendConfig(displayName = displayName, type = type)
    return base.copy(
        displayName = displayName.ifBlank { defaultName(type) },
        type = type,
        baseUrl = baseUrl.ifBlank { null },
        apiKeyOrToken = token.ifBlank { null },
        host = host.ifBlank { null },
        port = port.toIntOrNull(),
        useTls = useTls,
        modelName = modelName.ifBlank { null },
        useRunsApi = useRunsApi,
        useStreaming = useStreaming,
        isPrimary = isPrimary,
        secondaryUrls = secondaryUrls,
    )
}

private fun shortLabel(t: BackendType) = when (t) {
    BackendType.HERMES_API_SERVER -> "Hermes"
    BackendType.OPENCLAW_GATEWAY -> "OpenClaw Gateway"
    BackendType.OPENCLAW_HTTP -> "OpenClaw HTTP"
}

private fun defaultName(t: BackendType) = when (t) {
    BackendType.HERMES_API_SERVER -> "Hermes Agent"
    BackendType.OPENCLAW_GATEWAY -> "OpenClaw Gateway"
    BackendType.OPENCLAW_HTTP -> "OpenClaw HTTP"
}
