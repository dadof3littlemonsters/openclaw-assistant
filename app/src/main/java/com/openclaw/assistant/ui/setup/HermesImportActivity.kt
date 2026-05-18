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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

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
    var editable by remember(parsed) { mutableStateOf(parsed?.toEditablePairingPayload()) }
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
            editable?.let { draft ->
                PairingPayloadReviewEditor(
                    value = draft,
                    onChange = { editable = it },
                )
            }
            Spacer(Modifier.height(20.dp))
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = {
                    editable?.toPairingPayload()?.let {
                        applyPairingPayload(context, it, editable?.primaryBackendType())
                    }
                    onFinish()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.av_import_add_open)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val hermes = editable?.toPairingPayload()?.hermes
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

internal enum class PairingPrimaryChoice {
    HERMES,
    OPENCLAW,
}

internal data class EditablePairingPayload(
    val includeHermes: Boolean,
    val hermesBaseUrl: String,
    val hermesFallbackUrls: String,
    val hermesApiKey: String,
    val hermesModelName: String,
    val hermesUseRunsApi: Boolean,
    val hermesStreaming: Boolean,
    val hermesDisplayName: String,
    val includeOpenClaw: Boolean,
    val openClawSetupCode: String,
    val primaryChoice: PairingPrimaryChoice,
)

internal fun PairingPayload.toEditablePairingPayload(): EditablePairingPayload {
    val primary = when {
        hermes != null -> PairingPrimaryChoice.HERMES
        openClawSetupCode != null -> PairingPrimaryChoice.OPENCLAW
        else -> PairingPrimaryChoice.HERMES
    }
    return EditablePairingPayload(
        includeHermes = hermes != null,
        hermesBaseUrl = hermes?.baseUrl.orEmpty(),
        hermesFallbackUrls = hermes?.secondaryUrls?.joinToString("\n").orEmpty(),
        hermesApiKey = hermes?.apiKey.orEmpty(),
        hermesModelName = hermes?.modelName ?: "hermes-agent",
        hermesUseRunsApi = hermes?.useRunsApi ?: true,
        hermesStreaming = hermes?.streaming ?: true,
        hermesDisplayName = hermes?.displayName.orEmpty(),
        includeOpenClaw = openClawSetupCode != null,
        openClawSetupCode = openClawSetupCode.orEmpty(),
        primaryChoice = primary,
    )
}

internal fun EditablePairingPayload.toPairingPayload(): PairingPayload? {
    val hermes = if (includeHermes && hermesBaseUrl.trim().startsWith("http")) {
        HermesPairingPayload(
            baseUrl = hermesBaseUrl.trim(),
            secondaryUrls = hermesFallbackUrls
                .split("\n", ",")
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .filterNot { it == hermesBaseUrl.trim() },
            apiKey = hermesApiKey.trim().ifEmpty { null },
            modelName = hermesModelName.trim().ifEmpty { "hermes-agent" },
            useRunsApi = hermesUseRunsApi,
            streaming = hermesStreaming,
            displayName = hermesDisplayName.trim().ifEmpty { null },
        )
    } else {
        null
    }
    val openClaw = openClawSetupCode.trim().takeIf { includeOpenClaw && it.isNotEmpty() }
    return if (hermes == null && openClaw == null) null else PairingPayload(hermes, openClaw)
}

internal fun EditablePairingPayload.primaryBackendType(): BackendType? = when (primaryChoice) {
    PairingPrimaryChoice.HERMES -> if (includeHermes) BackendType.HERMES_API_SERVER else null
    PairingPrimaryChoice.OPENCLAW -> if (includeOpenClaw) BackendType.OPENCLAW_GATEWAY else null
}

@Composable
internal fun PairingPayloadReviewEditor(
    value: EditablePairingPayload,
    onChange: (EditablePairingPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.av_pairing_review_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.av_pairing_review_desc), style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = value.includeHermes,
                        onCheckedChange = {
                            onChange(value.copy(
                                includeHermes = it,
                                primaryChoice = if (it) value.primaryChoice else PairingPrimaryChoice.OPENCLAW,
                            ))
                        },
                    )
                    Text(stringResource(R.string.av_backend_hermes), style = MaterialTheme.typography.titleMedium)
                }
                if (value.includeHermes) {
                    OutlinedTextField(
                        value = value.hermesDisplayName,
                        onValueChange = { onChange(value.copy(hermesDisplayName = it)) },
                        label = { Text(stringResource(R.string.av_pairing_display_name)) },
                        placeholder = { Text(stringResource(R.string.av_backend_hermes)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = value.hermesBaseUrl,
                        onValueChange = { onChange(value.copy(hermesBaseUrl = it)) },
                        label = { Text(stringResource(R.string.av_import_primary_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = value.hermesFallbackUrls,
                        onValueChange = { onChange(value.copy(hermesFallbackUrls = it)) },
                        label = { Text(stringResource(R.string.av_import_fallback_urls)) },
                        placeholder = { Text(stringResource(R.string.av_pairing_fallback_urls_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = value.hermesApiKey,
                        onValueChange = { onChange(value.copy(hermesApiKey = it)) },
                        label = { Text(stringResource(R.string.av_import_api_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = value.hermesModelName,
                        onValueChange = { onChange(value.copy(hermesModelName = it)) },
                        label = { Text(stringResource(R.string.av_import_model)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    ToggleRow(
                        label = stringResource(R.string.av_pairing_runs_api),
                        checked = value.hermesUseRunsApi,
                        onCheckedChange = { onChange(value.copy(hermesUseRunsApi = it)) },
                    )
                    ToggleRow(
                        label = stringResource(R.string.av_pairing_streaming),
                        checked = value.hermesStreaming,
                        onCheckedChange = { onChange(value.copy(hermesStreaming = it)) },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = value.includeOpenClaw,
                        onCheckedChange = {
                            onChange(value.copy(
                                includeOpenClaw = it,
                                primaryChoice = if (it) value.primaryChoice else PairingPrimaryChoice.HERMES,
                            ))
                        },
                    )
                    Text(stringResource(R.string.av_backend_openclaw), style = MaterialTheme.typography.titleMedium)
                }
                if (value.includeOpenClaw) {
                    val decoded = remember(value.openClawSetupCode) {
                        GatewayConfigUtils.decodeGatewaySetupCode(value.openClawSetupCode)
                    }
                    OutlinedTextField(
                        value = value.openClawSetupCode,
                        onValueChange = { onChange(value.copy(openClawSetupCode = it)) },
                        label = { Text(stringResource(R.string.av_pairing_openclaw_setup_code)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    if (decoded != null) {
                        InfoRow(stringResource(R.string.av_pairing_openclaw_decoded), decoded.url)
                        InfoRow(stringResource(R.string.av_import_auth), when {
                            decoded.bootstrapToken != null && decoded.password != null -> stringResource(R.string.av_import_auth_password_pairing)
                            decoded.bootstrapToken != null -> stringResource(R.string.av_import_auth_bootstrap)
                            decoded.token != null -> stringResource(R.string.av_import_auth_token)
                            decoded.password != null -> stringResource(R.string.av_import_auth_password)
                            else -> stringResource(R.string.av_import_auth_none)
                        })
                    } else {
                        Text(stringResource(R.string.av_import_openclaw_invalid), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (value.includeHermes || value.includeOpenClaw) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.av_pairing_primary_title), style = MaterialTheme.typography.titleMedium)
                    if (value.includeHermes) {
                        PrimaryChoiceRow(
                            selected = value.primaryChoice == PairingPrimaryChoice.HERMES,
                            label = stringResource(R.string.av_pairing_primary_hermes),
                            onClick = { onChange(value.copy(primaryChoice = PairingPrimaryChoice.HERMES)) },
                        )
                    }
                    if (value.includeOpenClaw) {
                        PrimaryChoiceRow(
                            selected = value.primaryChoice == PairingPrimaryChoice.OPENCLAW,
                            label = stringResource(R.string.av_pairing_primary_openclaw),
                            onClick = { onChange(value.copy(primaryChoice = PairingPrimaryChoice.OPENCLAW)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrimaryChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

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
    return pairingPayloadCandidates(trimmed).firstNotNullOfOrNull { candidate ->
        runCatching { parsePairingJson(candidate) }.getOrNull()
    }
}

private fun pairingPayloadCandidates(trimmed: String): List<String> = buildList {
    add(trimmed)
    decodeUrlSafeBase64(trimmed)?.let { decoded ->
        if (decoded != trimmed) add(decoded)
    }
}

private fun decodeUrlSafeBase64(raw: String): String? {
    if (raw.startsWith("{") || raw.startsWith("[")) return null
    val compact = raw.trim().replace("\\s".toRegex(), "")
    if (compact.isEmpty()) return null
    return runCatching {
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = runCatching { Base64.getUrlDecoder().decode(padded) }
            .getOrElse { Base64.getDecoder().decode(padded) }
        String(bytes, Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.trim().startsWith("{") }
}

private fun parsePairingJson(raw: String): PairingPayload? {
    return runCatching {
        val obj = pairingJson.parseToJsonElement(raw.trim()).jsonObject
        parseAgentVoiceSetupJson(obj) ?: parseHermesRelayJson(obj)
    }.getOrNull()
}

private fun parseAgentVoiceSetupJson(obj: JsonObject): PairingPayload? {
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "agent_voice_setup") return null
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
                    useRunsApi = h["runs"]?.jsonPrimitive?.booleanOrNull ?: true,
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
    return if (hermes == null && openClawSetupCode == null) null else PairingPayload(hermes, openClawSetupCode)
}

private fun parseHermesRelayJson(obj: JsonObject): PairingPayload? {
    obj["hermes"]?.jsonPrimitive?.intOrNull ?: return null
    val endpointUrls = parseHermesRelayEndpointUrls(obj)
    val topLevelUrl = parseHermesRelayApiUrl(obj)
    val urls = (endpointUrls + listOfNotNull(topLevelUrl)).distinct()
    val base = urls.firstOrNull() ?: return null
    return PairingPayload(
        hermes = HermesPairingPayload(
            baseUrl = base,
            secondaryUrls = urls.drop(1),
            apiKey = obj["key"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
            modelName = obj["model"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { "hermes-agent" } ?: "hermes-agent",
            useRunsApi = obj["runs"]?.jsonPrimitive?.booleanOrNull ?: true,
            streaming = obj["streaming"]?.jsonPrimitive?.booleanOrNull ?: true,
            displayName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "Hermes Relay",
        ),
        openClawSetupCode = null,
    )
}

private fun parseHermesRelayEndpointUrls(obj: JsonObject): List<String> {
    val endpoints = obj["endpoints"] as? JsonArray ?: return emptyList()
    return endpoints.mapNotNull { element ->
        val endpoint = element as? JsonObject ?: return@mapNotNull null
        val api = endpoint["api"] as? JsonObject ?: return@mapNotNull null
        val priority = endpoint["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        priority to parseHermesRelayApiUrl(api)
    }
        .filter { (_, url) -> url != null }
        .sortedBy { (priority, _) -> priority }
        .mapNotNull { (_, url) -> url }
}

private fun parseHermesRelayApiUrl(obj: JsonObject): String? {
    val host = obj["host"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val port = obj["port"]?.jsonPrimitive?.intOrNull ?: 8642
    val tls = obj["tls"]?.jsonPrimitive?.booleanOrNull ?: false
    val scheme = if (tls) "https" else "http"
    val hostForUrl = if (":" in host && !host.startsWith("[")) "[$host]" else host
    return "$scheme://$hostForUrl:$port"
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
        useRunsApi = uri.getQueryParameter("${prefix}r") != "0",
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

internal fun applyPairingPayload(
    context: android.content.Context,
    payload: PairingPayload,
    primaryType: BackendType? = null,
) {
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
    primaryType?.let { desired ->
        val match = repo.backends.value.firstOrNull { config ->
            config.enabled && when (desired) {
                BackendType.HERMES_API_SERVER -> config.type == desired && payload.hermes?.let { hermes ->
                    val incomingUrls = buildSet {
                        add(hermes.baseUrl)
                        addAll(hermes.secondaryUrls)
                    }
                    config.baseUrl in incomingUrls || config.secondaryUrls.any { it in incomingUrls }
                } == true
                BackendType.OPENCLAW_GATEWAY -> config.type == desired
                BackendType.OPENCLAW_HTTP -> config.type == desired
            }
        } ?: repo.backends.value.firstOrNull { it.enabled && it.type == desired }
        match?.let { repo.setPrimary(it.id) }
    }
}
