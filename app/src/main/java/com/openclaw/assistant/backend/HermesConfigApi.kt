package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HermesModelOption(
    val id: String,
    val description: String? = null,
)

data class HermesConfigState(
    val model: String?,
    val provider: String?,
    val apiMode: String?,
    val baseUrl: String?,
)

data class HermesModelCatalog(
    val config: HermesConfigState?,
    val models: List<HermesModelOption>,
    val providers: List<String>,
)

class HermesConfigApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun fetchCatalog(config: AgentBackendConfig): HermesModelCatalog = withContext(Dispatchers.IO) {
        val current = fetchConfig(config)
        val baseUrl = requireBaseUrl(config)
        val catalog = getJson(config, HermesUrl.availableModelsUrl(baseUrl))
        val v1Models = getJson(config, HermesUrl.modelsUrl(baseUrl))
        val dashboardOptions = fetchDashboardModelOptions(config)
        val effectiveConfig = current ?: dashboardOptions?.let(::parseConfig)
        val models = (parseDashboardModels(dashboardOptions) + parseModels(catalog) + parseModels(v1Models))
            .ifEmpty {
                effectiveConfig?.model
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(HermesModelOption(it, "current")) }
                    .orEmpty()
            }
            .distinctBy { it.id }
        HermesModelCatalog(
            config = effectiveConfig,
            models = models,
            providers = (parseProviders(dashboardOptions) + parseProviders(catalog)).distinct(),
        )
    }

    suspend fun fetchConfig(config: AgentBackendConfig): HermesConfigState? = withContext(Dispatchers.IO) {
        getJson(config, HermesUrl.configUrl(requireBaseUrl(config)))?.let(::parseConfig)
    }

    suspend fun updateModel(config: AgentBackendConfig, model: String): HermesConfigState = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("model", model.trim()) }.toString().toRequestBody(JSON_MEDIA)
        val request = authed(config, Request.Builder().url(HermesUrl.configUrl(requireBaseUrl(config))).patch(body)).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${extractError(text).ifBlank { response.message }}")
            }
            parseConfig(json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun getJson(config: AgentBackendConfig, url: String): JsonObject? {
        val request = authed(config, Request.Builder().url(url).get()).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun getDashboardJson(config: AgentBackendConfig, url: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                config.terminalSessionToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("X-Hermes-Session-Token", it) }
            }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun fetchDashboardModelOptions(config: AgentBackendConfig): JsonObject? {
        val terminalUrl = config.terminalUrl?.takeIf { it.isNotBlank() } ?: return null
        val token = config.terminalSessionToken?.takeIf { it.isNotBlank() } ?: return null
        if (token.isBlank()) return null
        return getDashboardJson(config, HermesUrl.dashboardModelOptionsUrl(terminalUrl))
    }

    private fun authed(config: AgentBackendConfig, builder: Request.Builder): Request.Builder {
        config.apiKeyOrToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun requireBaseUrl(config: AgentBackendConfig): String =
        config.baseUrl?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Hermes backend has no base URL")

    private fun parseConfig(obj: JsonObject): HermesConfigState = HermesConfigState(
        model = obj["model"]?.jsonPrimitive?.contentOrNull,
        provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
        apiMode = obj["api_mode"]?.jsonPrimitive?.contentOrNull,
        baseUrl = obj["base_url"]?.jsonPrimitive?.contentOrNull,
    )

    private fun parseModels(obj: JsonObject?): List<HermesModelOption> {
        val array = (obj?.get("models") as? JsonArray)
            ?: (obj?.get("data") as? JsonArray)
            ?: (obj?.get("items") as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { item ->
            when (val value = item) {
                is JsonPrimitive -> value.contentOrNull?.let { HermesModelOption(it) }
                is JsonObject -> {
                    val id = value["id"]?.jsonPrimitive?.contentOrNull
                        ?: value["model"]?.jsonPrimitive?.contentOrNull
                        ?: value["name"]?.jsonPrimitive?.contentOrNull
                    id?.takeIf { it.isNotBlank() }?.let {
                        HermesModelOption(
                            id = it,
                            description = value["description"]?.jsonPrimitive?.contentOrNull
                                ?: value["owned_by"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
                else -> null
            }
        }
    }

    private fun parseDashboardModels(obj: JsonObject?): List<HermesModelOption> {
        val providers = obj?.get("providers") as? JsonArray ?: return emptyList()
        return providers.flatMap { providerItem ->
            val provider = providerItem as? JsonObject ?: return@flatMap emptyList()
            val providerName = providerName(provider)
            val models = provider["models"] as? JsonArray ?: return@flatMap emptyList()
            models.mapNotNull { modelItem ->
                when (modelItem) {
                    is JsonPrimitive -> modelItem.contentOrNull?.let { HermesModelOption(it, providerName) }
                    is JsonObject -> {
                        val id = modelItem["id"]?.jsonPrimitive?.contentOrNull
                            ?: modelItem["model"]?.jsonPrimitive?.contentOrNull
                            ?: modelItem["name"]?.jsonPrimitive?.contentOrNull
                        id?.takeIf { it.isNotBlank() }?.let {
                            HermesModelOption(
                                id = it,
                                description = modelItem["description"]?.jsonPrimitive?.contentOrNull
                                    ?: modelItem["label"]?.jsonPrimitive?.contentOrNull
                                    ?: providerName,
                            )
                        }
                    }
                    else -> null
                }
            }
        }
    }

    private fun parseProviders(obj: JsonObject?): List<String> {
        val array = obj?.get("providers")?.jsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> providerName(item)?.takeIf(String::isNotBlank)
                else -> null
            }
        }
    }

    private fun providerName(obj: JsonObject): String? =
        obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["provider"]?.jsonPrimitive?.contentOrNull
            ?: obj["id"]?.jsonPrimitive?.contentOrNull
            ?: obj["slug"]?.jsonPrimitive?.contentOrNull

    private fun extractError(text: String): String = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault(text).take(300)

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
