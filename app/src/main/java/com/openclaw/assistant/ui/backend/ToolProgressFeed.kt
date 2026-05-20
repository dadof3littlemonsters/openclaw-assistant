package com.openclaw.assistant.ui.backend

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.backend.AgentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared tool-progress feed. Producers (Hermes/OpenClaw client code) push
 * [AgentEvent.ToolProgress] events here; Chat renders the most
 * recent few as inline cards above the input so the user can see tool
 * activity in real time.
 *
 * Bounded to 5 entries (newest first) — older progress lines drop off so
 * the strip never grows unbounded across long sessions.
 */
object ToolProgressFeed {
    private const val CAP = 5
    private val _events = MutableStateFlow<List<AgentEvent.ToolProgress>>(emptyList())
    val events: StateFlow<List<AgentEvent.ToolProgress>> = _events.asStateFlow()

    fun push(ev: AgentEvent.ToolProgress) {
        val list = (listOf(ev) + _events.value).take(CAP)
        _events.value = list
    }

    fun clear() { _events.value = emptyList() }
}

@Composable
fun ToolProgressStrip(modifier: Modifier = Modifier) {
    val events by ToolProgressFeed.events.collectAsState()
    if (events.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        events.forEach { ev ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(modifier = Modifier.padding(8.dp)) {
                    Text("🔧 ${ev.tool}", style = MaterialTheme.typography.labelMedium)
                    Text(" · ${ev.stage}", style = MaterialTheme.typography.labelSmall)
                    ev.detail?.let { Text("  $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
