package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AgentContextInspection(
    val contextName: String?,
    val contextDetail: String?,
    val summary: String,
)

/**
 * Best-effort read-only inspector for agent metadata exposed by Hermes-like
 * API servers. Every endpoint is optional; unsupported servers simply return a
 * short "not available" summary.
 */
class AgentContextInspector(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun inspect(config: AgentBackendConfig): AgentContextInspection = withContext(Dispatchers.IO) {
        val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext AgentContextInspection(null, null, "No base URL configured")
        val apiBase = HermesUrl.apiBase(baseUrl)
        val headers = config.apiKeyOrToken?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        val profiles = getJson("$apiBase/api/profiles", headers)
        val configJson = getJson("$apiBase/api/config", headers)
        val skillsJson = getJson("$apiBase/api/skills", headers)
        val soulJson = getJson("$apiBase/api/profiles/default/soul", headers)
        val memoryJson = getJson("$apiBase/api/profiles/default/memory", headers)

        val profileName = firstProfileName(profiles)
            ?: config.agentContextName
            ?: config.modelName
        val model = firstProfileModel(profiles) ?: config.modelName
        val personality = configJson?.let(::defaultPersonality)
        val skillsCount = countArray(skillsJson, "skills")
        val memoryCount = countArray(memoryJson, "entries")
        val soulState = soulJson?.jsonObject?.get("exists")?.jsonPrimitive?.contentOrNull
        val detailParts = listOfNotNull(
            model?.takeIf { it.isNotBlank() }?.let { "model: $it" },
            personality?.takeIf { it.isNotBlank() }?.let { "personality: $it" },
            skillsCount?.let { "skills: $it" },
            memoryCount?.let { "memory: $it" },
            soulState?.let { "SOUL: $it" },
        )
        AgentContextInspection(
            contextName = profileName?.takeIf { it.isNotBlank() },
            contextDetail = detailParts.joinToString(" · ").ifBlank { null },
            summary = if (profiles == null && configJson == null && skillsJson == null && soulJson == null && memoryJson == null) {
                "No optional agent metadata endpoints responded. Manual context fields can still be used."
            } else {
                detailParts.joinToString("\n").ifBlank { "Agent metadata endpoint responded, but no displayable fields were found." }
            },
        )
    }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject? = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            json.parseToJsonElement(body).jsonObject
        }
    }.getOrNull()

    private fun firstProfileName(obj: JsonObject?): String? {
        val array = obj?.get("profiles") as? JsonArray ?: obj?.get("items") as? JsonArray ?: return null
        return array.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
    }

    private fun firstProfileModel(obj: JsonObject?): String? {
        val array = obj?.get("profiles") as? JsonArray ?: obj?.get("items") as? JsonArray ?: return null
        return array.firstOrNull()?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
    }

    private fun defaultPersonality(obj: JsonObject): String? {
        val display = obj["display"]?.jsonObject
        return display?.get("personality")?.jsonPrimitive?.contentOrNull
            ?: obj["personality"]?.jsonPrimitive?.contentOrNull
    }

    private fun countArray(obj: JsonObject?, key: String): Int? =
        obj?.get(key)?.jsonArray?.size
}
