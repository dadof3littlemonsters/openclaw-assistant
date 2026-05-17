package com.openclaw.assistant.ui.backend

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.backend.BackendManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide, ephemeral "where should the next chat message go?" pointer.
 * Persisted only in memory — the wake word and Voice Overlay always use the
 * persisted Primary; the Chat UI may temporarily override.
 */
object ChatBackendTarget {
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId
    fun set(id: String?) { _selectedId.value = id }
}

/**
 * Compact selector chip + dropdown for the Chat top bar. Lists every enabled
 * backend the user has configured plus a "Primary" entry that defers to
 * [BackendManager.primaryClient].
 */
@Composable
fun ChatBackendSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { BackendManager.getInstance(context) }
    val backends by manager.backends.collectAsState()
    val selectedId by ChatBackendTarget.selectedId.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val enabled = backends.filter { it.enabled }
    val primary = enabled.firstOrNull { it.isPrimary }
    val resolved = enabled.firstOrNull { it.id == selectedId } ?: primary
    val label = when {
        resolved == null -> "No backend"
        selectedId == null -> "Primary · ${primary?.displayName ?: "—"}"
        else -> resolved.displayName
    }

    Row(modifier = modifier.padding(start = 0.dp)) {
        AssistChip(onClick = { expanded = enabled.isNotEmpty() }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Primary backend") }, onClick = { ChatBackendTarget.set(null); expanded = false })
            enabled.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.displayName) },
                    onClick = { ChatBackendTarget.set(b.id); expanded = false },
                )
            }
        }
    }
}
