package com.openclaw.assistant.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deep-link target for external-camera Agent Voice setup links. App-internal QR
 * scanning uses the JSON form directly, while this Activity keeps deep-link
 * compatibility:
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(stringResource(R.string.av_import_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            if (parsed == null) {
                Text(stringResource(R.string.av_import_missing), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.av_import_close)) }
                return@Column
            }
            Text(stringResource(R.string.av_import_review), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            parsed.hermes?.let { HermesSummary(it) }
            parsed.openClawSetupCode?.let { code ->
                Spacer(Modifier.height(12.dp))
                OpenClawSummary(code)
            }
            Spacer(Modifier.height(20.dp))
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = {
                    applyPairingPayload(context, parsed)
                    onFinish()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.av_import_add_open)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val hermes = parsed.hermes
                    if (hermes == null) {
                        status = context.getString(R.string.av_import_no_hermes)
                        return@OutlinedButton
                    }
                    val config = hermes.toBackendConfig(isPrimary = false)
                    scope.launch {
                        status = context.getString(R.string.av_connection_testing)
                        val r = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                        status = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.av_import_test_hermes)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.av_import_cancel)) }
        }
    }
}

@Composable
private fun HermesSummary(hermes: HermesPairingPayload) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.av_backend_hermes), style = MaterialTheme.typography.titleMedium)
            InfoRow(stringResource(R.string.av_import_primary_url), hermes.baseUrl)
            if (hermes.secondaryUrls.isNotEmpty()) InfoRow(stringResource(R.string.av_import_fallback_urls), hermes.secondaryUrls.joinToString("\n"))
            InfoRow(stringResource(R.string.av_import_api_key), if (hermes.apiKey.isNullOrBlank()) stringResource(R.string.av_import_not_included) else mask(hermes.apiKey))
            InfoRow(stringResource(R.string.av_import_model), hermes.modelName)
            InfoRow(stringResource(R.string.av_import_mode), if (hermes.useRunsApi) stringResource(R.string.av_import_mode_runs) else stringResource(R.string.av_import_mode_chat))
        }
    }
}

@Composable
private fun OpenClawSummary(setupCode: String) {
    val decoded = GatewayConfigUtils.decodeGatewaySetupCode(setupCode)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.av_backend_openclaw), style = MaterialTheme.typography.titleMedium)
            if (decoded != null) {
                InfoRow(stringResource(R.string.av_import_gateway_url), decoded.url)
                InfoRow(stringResource(R.string.av_import_auth), when {
                    decoded.bootstrapToken != null && decoded.password != null -> stringResource(R.string.av_import_auth_password_pairing)
                    decoded.bootstrapToken != null -> stringResource(R.string.av_import_auth_bootstrap)
                    decoded.token != null -> stringResource(R.string.av_import_auth_token)
                    decoded.password != null -> stringResource(R.string.av_import_auth_password)
                    else -> stringResource(R.string.av_import_auth_none)
                })
            } else {
                Text(stringResource(R.string.av_import_openclaw_invalid), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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

internal fun parsePairingPayload(raw: String): PairingPayload? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("agentvoice://")) {
        return runCatching { parsePairingUri(Uri.parse(trimmed)) }.getOrNull()
    }
    return runCatching {
        val obj = pairingJson.parseToJsonElement(trimmed).jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "agent_voice_setup") return@runCatching null
        val hermesObj = obj["hermes"] as? JsonObject
        val hermes = hermesObj?.let { h ->
            val urls = (h["urls"] as? JsonArray)
                ?.mapNotNull { element ->
                    element.jsonPrimitive.contentOrNull?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
                .orEmpty()
            val base = urls.firstOrNull()
                ?: h["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            base?.let {
                HermesPairingPayload(
                    baseUrl = it,
                    secondaryUrls = urls.drop(1),
                    apiKey = h["key"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                    modelName = h["model"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { "hermes-agent" } ?: "hermes-agent",
                    useRunsApi = h["runs"]?.jsonPrimitive?.booleanOrNull ?: false,
                    streaming = h["streaming"]?.jsonPrimitive?.booleanOrNull ?: true,
                    displayName = h["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                )
            }
        }
        val openClawSetupCode = (obj["openclaw"] as? JsonObject)
            ?.get("setupCode")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.ifEmpty { null }
        if (hermes == null && openClawSetupCode == null) null else PairingPayload(hermes, openClawSetupCode)
    }.getOrNull()
}

private val pairingJson = Json { ignoreUnknownKeys = true; isLenient = true }

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

internal fun applyPairingPayload(context: android.content.Context, payload: PairingPayload) {
    val repo = BackendRepository.getInstance(context)
    payload.hermes?.let { hermes ->
        val current = repo.backends.value
        val incomingUrls = buildSet {
            add(hermes.baseUrl)
            addAll(hermes.secondaryUrls)
        }
        val displayName = hermes.displayName ?: "Hermes Agent"
        val duplicates = current.filter { existing ->
            existing.type == BackendType.HERMES_API_SERVER &&
                (existing.displayName == displayName ||
                    existing.baseUrl?.let { it in incomingUrls } == true ||
                    existing.secondaryUrls.any { it in incomingUrls })
        }
        val target = duplicates.firstOrNull { it.isPrimary } ?: duplicates.firstOrNull()
        val incomingConfig = hermes.toBackendConfig(isPrimary = target?.isPrimary ?: current.none { it.isPrimary && it.enabled })
        val config = incomingConfig
            .copy(
                id = target?.id ?: incomingConfig.id,
                createdAt = target?.createdAt ?: System.currentTimeMillis(),
            )
        duplicates.filterNot { it.id == config.id }.forEach { repo.delete(it.id) }
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
        runtime.setGatewayBootstrapToken(decoded.bootstrapToken.orEmpty())
        runtime.setGatewayPassword(decoded.password.orEmpty())
        runtime.setGatewayToken("")
        runtime.prefs.saveGatewayToken(decoded.token.orEmpty())
        settings.authToken = decoded.token.orEmpty()
        if (decoded.token != null && decoded.password != null) {
            // Token takes precedence in GatewaySession; keep imported setup codes
            // deterministic by not retaining a lower-priority password alongside it.
            runtime.setGatewayPassword("")
        }
        GatewayConfigUtils.composeGatewayManualUrl(parsed.host, parsed.port.toString(), parsed.tls)
            ?.let { url ->
                if (com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(url)) {
                    settings.httpUrl = url
                }
            }
        runtime.setManualEnabled(true)
        settings.connectionType = SettingsRepository.CONNECTION_TYPE_GATEWAY
        val current = repo.backends.value
        val duplicates = current.filter { existing ->
            existing.type == BackendType.OPENCLAW_GATEWAY &&
                (existing.displayName == "OpenClaw Gateway" ||
                    (existing.host == parsed.host && existing.port == parsed.port))
        }
        val target = duplicates.firstOrNull { it.isPrimary } ?: duplicates.firstOrNull()
        val gatewayConfig = AgentBackendConfig(
            id = target?.id ?: AgentBackendConfig(
                displayName = "OpenClaw Gateway",
                type = BackendType.OPENCLAW_GATEWAY,
            ).id,
            displayName = "OpenClaw Gateway",
            type = BackendType.OPENCLAW_GATEWAY,
            host = parsed.host,
            port = parsed.port,
            useTls = parsed.tls,
            baseUrl = parsed.displayUrl,
            apiKeyOrToken = decoded.token ?: decoded.password ?: decoded.bootstrapToken,
            isPrimary = target?.isPrimary ?: current.none { it.isPrimary && it.enabled },
            createdAt = target?.createdAt ?: System.currentTimeMillis(),
        )
        duplicates.filterNot { it.id == gatewayConfig.id }.forEach { repo.delete(it.id) }
        repo.upsert(gatewayConfig)
        if (gatewayConfig.isPrimary) repo.setPrimary(gatewayConfig.id)
    }
}
