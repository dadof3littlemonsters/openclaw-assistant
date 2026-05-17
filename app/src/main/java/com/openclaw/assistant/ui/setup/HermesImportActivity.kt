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
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.utils.GatewayConfigUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deep-link target for Agent Voice setup QR URIs. The desktop helper prints one
 * QR that can include Hermes plus OpenClaw settings:
 *
 *   agentvoice://setup?hu=...&hk=...&oc=...
 *
 * The older Hermes-only `agentvoice://hermes/setup?u=...` form is still
 * accepted for compatibility.
 *
 * Accepted query parameters:
 * Hermes-only compatibility parameters:
 *   u  — base URL. Multiple `u=` params are stored as
 *        secondary URLs for the endpoint racer (LAN + Tailscale + public).
 *   k  — API key (optional but recommended).
 *   m  — model name (optional, defaults to `hermes-agent`).
 *   r  — `1` to default to Runs API, `0` for chat completions.
 *   s  — `1` to enable streaming (default), `0` to disable.
 *   n  — display name (optional).
 *
 * Combined setup parameters:
 *   hu/hk/hm/hr/hs/hn mirror the Hermes-only params.
 *   oc is an OpenClaw Gateway setup code, as printed by `openclaw qr`.
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
        Text("Add Agent Voice backends", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (parsed == null) {
            Text("This pairing link is missing required information.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCancel) { Text("Close") }
            return@Column
        }
        Text("From the QR you scanned:", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        parsed.hermes?.let { hermes ->
            InfoRow("Hermes URL", hermes.baseUrl)
            if (hermes.secondaryUrls.isNotEmpty()) InfoRow("Hermes extra URLs", hermes.secondaryUrls.joinToString("\n"))
            InfoRow("Hermes API key", if (hermes.apiKey.isNullOrBlank()) "(none)" else mask(hermes.apiKey))
            InfoRow("Hermes model", hermes.modelName)
            InfoRow("Hermes mode", if (hermes.useRunsApi) "Runs API" else "Chat completions")
        }
        parsed.openClawSetupCode?.let { code ->
            InfoRow("OpenClaw", if (GatewayConfigUtils.decodeGatewaySetupCode(code) != null) "Setup code included" else "Invalid setup code")
        }
        Spacer(Modifier.height(16.dp))
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                applyPairingPayload(context, parsed)
                onFinish()
            }) { Text("Add & open Agent Voice") }
            OutlinedButton(onClick = {
                val hermes = parsed.hermes
                if (hermes == null) {
                    status = "No Hermes backend in this QR."
                    return@OutlinedButton
                }
                val config = hermes.toBackendConfig(isPrimary = false)
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
    val hermes: HermesPairingPayload?,
    val openClawSetupCode: String?,
)

internal data class HermesPairingPayload(
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
    val hermes = parseHermesParams(uri, prefix = if (uri.host == "setup") "h" else "")
    val openClawSetupCode = uri.getQueryParameter("oc")?.trim()?.ifEmpty { null }
    if (hermes == null && openClawSetupCode == null) return null
    return PairingPayload(
        hermes = hermes,
        openClawSetupCode = openClawSetupCode,
    )
}

private fun parseHermesParams(uri: Uri, prefix: String): HermesPairingPayload? {
    val urls = uri.getQueryParameters("${prefix}u")
    val base = urls.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val secondary = urls.drop(1).filter { it.startsWith("http://") || it.startsWith("https://") }
    return HermesPairingPayload(
        baseUrl = base,
        secondaryUrls = secondary,
        apiKey = uri.getQueryParameter("${prefix}k"),
        modelName = uri.getQueryParameter("${prefix}m")?.ifBlank { null } ?: "hermes-agent",
        useRunsApi = uri.getQueryParameter("${prefix}r") == "1",
        streaming = uri.getQueryParameter("${prefix}s") != "0",
        displayName = uri.getQueryParameter("${prefix}n"),
    )
}

private fun HermesPairingPayload.toBackendConfig(isPrimary: Boolean): AgentBackendConfig = AgentBackendConfig(
    displayName = displayName ?: "Hermes Agent",
    type = BackendType.HERMES_API_SERVER,
    baseUrl = baseUrl,
    secondaryUrls = secondaryUrls,
    apiKeyOrToken = apiKey,
    modelName = modelName,
    useRunsApi = useRunsApi,
    useStreaming = streaming,
    isPrimary = isPrimary,
)

private fun applyPairingPayload(context: android.content.Context, payload: PairingPayload) {
    val repo = BackendRepository.getInstance(context)
    payload.hermes?.let { hermes ->
        val config = hermes.toBackendConfig(isPrimary = repo.backends.value.isEmpty())
        repo.upsert(config)
        if (config.isPrimary) repo.setPrimary(config.id)
    }
    payload.openClawSetupCode?.let { code ->
        val decoded = GatewayConfigUtils.decodeGatewaySetupCode(code) ?: return@let
        val parsed = GatewayConfigUtils.parseGatewayEndpoint(decoded.url) ?: return@let
        val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        val settings = SettingsRepository.getInstance(context)
        runtime.setManualHost(parsed.host)
        runtime.setManualPort(parsed.port)
        runtime.setManualTls(parsed.tls)
        when {
            decoded.bootstrapToken != null -> runtime.setGatewayBootstrapToken(decoded.bootstrapToken)
            decoded.token != null -> {
                runtime.prefs.saveGatewayToken(decoded.token)
                settings.authToken = decoded.token
            }
            decoded.password != null -> runtime.setGatewayPassword(decoded.password)
        }
        GatewayConfigUtils.composeGatewayManualUrl(parsed.host, parsed.port.toString(), parsed.tls)
            ?.let { url ->
                if (com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(url)) {
                    settings.httpUrl = url
                }
            }
        runtime.setManualEnabled(true)
        settings.connectionType = SettingsRepository.CONNECTION_TYPE_GATEWAY
    }
}
