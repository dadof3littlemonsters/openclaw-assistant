package com.openclaw.assistant.backend

import com.openclaw.assistant.node.NodeRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TimeZone

@Serializable
data class OpenClawCronListResult(
    val jobs: List<OpenClawCronJob> = emptyList(),
    val deliveryPreviews: Map<String, OpenClawCronDeliveryPreview> = emptyMap(),
)

@Serializable
data class OpenClawCronDeliveryPreview(
    val label: String? = null,
    val detail: String? = null,
)

@Serializable
data class OpenClawCronJob(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val schedule: OpenClawCronSchedule,
    val payload: OpenClawCronPayload,
    val delivery: OpenClawCronDelivery? = null,
    val state: OpenClawCronState? = null,
)

@Serializable
data class OpenClawCronSchedule(
    val kind: String,
    val expr: String? = null,
    val tz: String? = null,
    val everyMs: Long? = null,
    val at: String? = null,
)

@Serializable
data class OpenClawCronPayload(
    val kind: String,
    val message: String? = null,
    val text: String? = null,
)

@Serializable
data class OpenClawCronDelivery(
    val mode: String,
    val channel: String? = null,
    val to: String? = null,
)

@Serializable
data class OpenClawCronState(
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastRunStatus: String? = null,
    val lastStatus: String? = null,
    val lastDeliveryStatus: String? = null,
    val consecutiveErrors: Int? = null,
    val lastError: String? = null,
    val lastDiagnosticSummary: String? = null,
)

class OpenClawCronApi(
    private val runtime: NodeRuntime,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun fetchJobs(): OpenClawCronListResult {
        val response = runtime.requestGateway(
            method = "cron.list",
            paramsJson = """{"includeDisabled":true,"limit":200}""",
            timeoutMs = 30_000,
        )
        return json.decodeFromString(OpenClawCronListResult.serializer(), response)
    }

    suspend fun createJob(name: String, expr: String, prompt: String): OpenClawCronJob {
        val params = buildCronJobParams(name, expr, prompt, payloadKind = "agentTurn")
        val response = runtime.requestGateway("cron.add", params.toString(), timeoutMs = 30_000)
        return json.decodeFromString(OpenClawCronJob.serializer(), response)
    }

    suspend fun updateJob(
        job: OpenClawCronJob,
        name: String? = null,
        expr: String? = null,
        prompt: String? = null,
        enabled: Boolean? = null,
    ): OpenClawCronJob {
        val payloadKind = job.payload.kind.ifBlank { "agentTurn" }
        val patch = buildJsonObject {
            if (name != null) put("name", name.trim())
            if (enabled != null) put("enabled", enabled)
            if (expr != null) {
                put("schedule", buildCronSchedule(expr))
            }
            if (prompt != null) {
                put("payload", buildCronPayload(payloadKind, prompt))
            }
        }
        val params = buildJsonObject {
            put("id", job.id)
            put("patch", patch)
        }
        val response = runtime.requestGateway("cron.update", params.toString(), timeoutMs = 30_000)
        return json.decodeFromString(OpenClawCronJob.serializer(), response)
    }

    suspend fun deleteJob(jobId: String) {
        runtime.requestGateway("cron.remove", """{"id":${quote(jobId)}}""", timeoutMs = 30_000)
    }

    private fun buildCronJobParams(name: String, expr: String, prompt: String, payloadKind: String) =
        buildJsonObject {
            put("name", name.trim())
            put("enabled", true)
            put("schedule", buildCronSchedule(expr))
            put("sessionTarget", "isolated")
            put("wakeMode", "now")
            put("payload", buildCronPayload(payloadKind, prompt))
            put(
                "delivery",
                buildJsonObject {
                    put("mode", "none")
                },
            )
        }

    private fun buildCronSchedule(expr: String) =
        buildJsonObject {
            put("kind", "cron")
            put("expr", expr.trim())
            put("tz", TimeZone.getDefault().id)
        }

    private fun buildCronPayload(kind: String, prompt: String) =
        buildJsonObject {
            when (kind) {
                "systemEvent" -> {
                    put("kind", "systemEvent")
                    put("text", prompt.trim())
                }
                else -> {
                    put("kind", "agentTurn")
                    put("message", prompt.trim())
                }
            }
        }

    private fun quote(value: String): String = buildJsonObject { put("value", value) }["value"].toString()
}
