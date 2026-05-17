package com.openclaw.assistant.ui.setup

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deep-link target for `agentvoice://hermes/setup?...` URIs. The companion
 * `hermes-pair` CLI on the desktop prints a QR encoding such a URI. The user
 * scans it with any QR reader on the phone (the stock camera app on Pixel /
 * recent Samsung devices already does this); Android resolves the scheme to
 * this Activity, the params are parsed, and a Hermes backend is added
 * automatically — no typing required.
 *
 * Accepted query parameters:
 *   u  — base URL (required). Multiple `u=` params are stored as
 *        secondary URLs for the endpoint racer (LAN + Tailscale + public).
 *   k  — API key (optional but recommended).
 *   m  — model name (optional, defaults to `hermes-agent`).
 *   r  — `1` to default to Runs API, `0` for chat completions.
 *   s  — `1` to enable streaming (default), `0` to disable.
 *   n  — display name (optional).
 */
class HermesImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent?.data
        setContent { MaterialTheme { ImportScreen(uri, onFinish = ::done, onCancel = ::cancel) } }
    }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    private fun cancel() { finish() }
}

@Composable
private fun ImportScreen(uri: Uri?, onFinish: () -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed = remember(uri) { uri?.let(::parsePairingUri) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Add Hermes Agent", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (parsed == null) {
            Text("This pairing link is missing required information.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCancel) { Text("Close") }
            return@Column
        }
        Text("From the QR you scanned:", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        InfoRow("URL", parsed.baseUrl)
        if (parsed.secondaryUrls.isNotEmpty()) InfoRow("Extra URLs", parsed.secondaryUrls.joinToString("\n"))
        InfoRow("API key", if (parsed.apiKey.isNullOrBlank()) "(none)" else mask(parsed.apiKey))
        InfoRow("Model", parsed.modelName)
        InfoRow("Mode", if (parsed.useRunsApi) "Runs API" else "Chat completions")
        Spacer(Modifier.height(16.dp))
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val repo = BackendRepository.getInstance(context)
                val config = AgentBackendConfig(
                    displayName = parsed.displayName ?: "Hermes Agent",
                    type = BackendType.HERMES_API_SERVER,
                    baseUrl = parsed.baseUrl,
                    secondaryUrls = parsed.secondaryUrls,
                    apiKeyOrToken = parsed.apiKey,
                    modelName = parsed.modelName,
                    useRunsApi = parsed.useRunsApi,
                    useStreaming = parsed.streaming,
                    isPrimary = repo.backends.value.isEmpty(),
                )
                repo.upsert(config)
                if (config.isPrimary) repo.setPrimary(config.id)
                onFinish()
            }) { Text("Add & open AgentVoice") }
            OutlinedButton(onClick = {
                val repo = BackendRepository.getInstance(context)
                val config = AgentBackendConfig(
                    displayName = parsed.displayName ?: "Hermes Agent",
                    type = BackendType.HERMES_API_SERVER,
                    baseUrl = parsed.baseUrl,
                    secondaryUrls = parsed.secondaryUrls,
                    apiKeyOrToken = parsed.apiKey,
                    modelName = parsed.modelName,
                    useRunsApi = parsed.useRunsApi,
                    useStreaming = parsed.streaming,
                )
                scope.launch {
                    status = "Testing…"
                    val r = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                    status = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"
                }
            }) { Text("Test first") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun mask(s: String): String = if (s.length <= 6) "•".repeat(s.length) else s.take(3) + "…" + s.takeLast(2)

internal data class PairingPayload(
    val baseUrl: String,
    val secondaryUrls: List<String>,
    val apiKey: String?,
    val modelName: String,
    val useRunsApi: Boolean,
    val streaming: Boolean,
    val displayName: String?,
)

/**
 * Parses `agentvoice://hermes/setup?u=...&k=...&m=...&r=...&s=...&n=...` URIs.
 * Multiple `u=` params are supported (the first is canonical baseUrl, the
 * rest go into [AgentBackendConfig.secondaryUrls] for the endpoint racer).
 */
internal fun parsePairingUri(uri: Uri): PairingPayload? {
    if (uri.scheme != "agentvoice") return null
    val urls = uri.getQueryParameters("u")
    val base = urls.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val secondary = urls.drop(1).filter { it.startsWith("http://") || it.startsWith("https://") }
    return PairingPayload(
        baseUrl = base,
        secondaryUrls = secondary,
        apiKey = uri.getQueryParameter("k"),
        modelName = uri.getQueryParameter("m")?.ifBlank { null } ?: "hermes-agent",
        useRunsApi = uri.getQueryParameter("r") == "1",
        streaming = uri.getQueryParameter("s") != "0",
        displayName = uri.getQueryParameter("n"),
    )
}

