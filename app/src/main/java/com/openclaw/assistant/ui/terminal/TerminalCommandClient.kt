package com.openclaw.assistant.ui.terminal

import android.content.Context
import com.openclaw.assistant.OpenClawApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TerminalCommandClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun isConfigured(context: Context): Boolean {
        val prefs = (context.applicationContext as OpenClawApplication).nodeRuntime.prefs
        return !prefs.loadTerminalCommandUrl().isNullOrBlank() && !prefs.loadTerminalCommandSecret().isNullOrBlank()
    }

    suspend fun run(context: Context, command: String, timeoutSeconds: Int = 30): Result<CommandResult> =
        withContext(Dispatchers.IO) {
            val prefs = (context.applicationContext as OpenClawApplication).nodeRuntime.prefs
            val url = prefs.loadTerminalCommandUrl()
                ?: return@withContext Result.failure(IllegalStateException("Terminal command endpoint is not configured"))
            val secret = prefs.loadTerminalCommandSecret()
                ?: return@withContext Result.failure(IllegalStateException("Terminal command secret is not configured"))
            val body = JSONObject()
                .put("secret", secret)
                .put("command", command)
                .put("timeoutSeconds", timeoutSeconds)
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    val ok = response.isSuccessful && obj?.optBoolean("ok") == true
                    if (!ok) {
                        val message = obj?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: obj?.optString("stderr")?.takeIf { it.isNotBlank() }
                            ?: obj?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Terminal command failed"
                        return@withContext Result.failure(IllegalStateException(message))
                    }
                    Result.success(
                        CommandResult(
                            exitCode = obj.optInt("exitCode", 0),
                            stdout = obj.optString("stdout"),
                            stderr = obj.optString("stderr"),
                        ),
                    )
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
