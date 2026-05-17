package com.openclaw.assistant.ui.setup

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.data.SettingsRepository

/**
 * Simplified first-run wizard.
 *
 *   Step 1 — Welcome
 *   Step 2 — Tick the backends you want to set up (Hermes / OpenClaw)
 *   Step 3 — Per-backend setup, only for ticked options:
 *              • Hermes: scan the QR that `hermes-pair` prints on your PC.
 *                Any QR scanner on the phone routes to HermesImportActivity
 *                automatically, so we just show the instructions and a
 *                manual paste fallback.
 *              • OpenClaw: run `openclaw qr` on the server and tap Scan in
 *                the existing OpenClaw setup screen (we open it directly).
 *   Step 4 — Done
 */
class AgentVoiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SimpleSetupWizard { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSetupWizard(onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val backends by repo.backends.collectAsState()
    var step by remember { mutableStateOf(0) }
    var pickHermes by remember { mutableStateOf(true) }
    var pickOpenClaw by remember { mutableStateOf(false) }
    val totalSteps = 4

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.av_setup_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            LinearProgressIndicator(progress = { (step + 1f) / totalSteps }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            when (step) {
                0 -> WelcomePane()
                1 -> ChooseBackendsPane(
                    pickHermes = pickHermes, pickOpenClaw = pickOpenClaw,
                    onHermes = { pickHermes = it }, onOpenClaw = { pickOpenClaw = it },
                )
                2 -> ConfigurePane(pickHermes, pickOpenClaw, backends.size)
                3 -> DonePane(backends)
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step in 1 until totalSteps - 1) OutlinedButton(onClick = { step-- }) { Text(stringResource(R.string.av_setup_back)) }
                Spacer(Modifier.weight(1f))
                if (step < totalSteps - 1) {
                    Button(
                        onClick = { step++ },
                        enabled = step != 1 || (pickHermes || pickOpenClaw),
                    ) { Text(if (step == 0) stringResource(R.string.av_setup_get_started) else stringResource(R.string.av_setup_next)) }
                } else {
                    Button(onClick = onDone) { Text(stringResource(R.string.av_setup_finish)) }
                }
            }
        }
    }
}

@Composable private fun WelcomePane() {
    Text(stringResource(R.string.av_setup_welcome_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.av_setup_welcome_body), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ChooseBackendsPane(
    pickHermes: Boolean,
    pickOpenClaw: Boolean,
    onHermes: (Boolean) -> Unit,
    onOpenClaw: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.av_setup_choose_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.av_setup_choose_hint), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))

    BackendChoiceCard(
        title = stringResource(R.string.av_backend_hermes),
        subtitle = stringResource(R.string.av_backend_hermes_subtitle),
        checked = pickHermes, onCheckedChange = onHermes,
    )
    Spacer(Modifier.height(12.dp))
    BackendChoiceCard(
        title = stringResource(R.string.av_backend_openclaw),
        subtitle = stringResource(R.string.av_backend_openclaw_subtitle),
        checked = pickOpenClaw, onCheckedChange = onOpenClaw,
    )
}

@Composable
private fun BackendChoiceCard(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, colors = CheckboxDefaults.colors())
            Spacer(Modifier.height(0.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConfigurePane(pickHermes: Boolean, pickOpenClaw: Boolean, existingBackends: Int) {
    val context = LocalContext.current
    if (pickHermes) HermesQrCard()
    if (pickHermes && pickOpenClaw) Spacer(Modifier.height(16.dp))
    if (pickOpenClaw) OpenClawCard(onLaunch = {
        // Launch the existing OpenClaw setup guide by reopening MainActivity — the
        // built-in SetupGuideScreen will surface as long as hasCompletedSetup is
        // still false. If the user has already completed OpenClaw setup once,
        // they can use Settings → Backends → Add → OpenClaw Gateway.
        context.startActivity(
            Intent(context, com.openclaw.assistant.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    })
    if (pickHermes && !pickOpenClaw) {
        Spacer(Modifier.height(16.dp))
        ManualHermesFallback()
    }
}

@Composable
private fun HermesQrCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.av_hermes_card_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_hermes_card_step1), style = MaterialTheme.typography.bodyMedium)
            CodeBlock("pip install qrcode    # one-time")
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_hermes_card_step2), style = MaterialTheme.typography.bodyMedium)
            CodeBlock(
                "python hermes_pair.py \\\n" +
                    "  --url http://192.168.1.42:8642 \\\n" +
                    "  --key sk-…",
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_hermes_card_step3), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_hermes_card_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OpenClawCard(onLaunch: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.av_openclaw_card_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_openclaw_card_step1), style = MaterialTheme.typography.bodyMedium)
            CodeBlock("openclaw qr")
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_openclaw_card_step2), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLaunch) { Text(stringResource(R.string.av_backend_open_openclaw_setup)) }
        }
    }
}

@Composable
private fun ManualHermesFallback() {
    val context = LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("hermes-agent") }
    var status by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.av_hermes_manual_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.av_hermes_manual_url)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.av_hermes_manual_key)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(stringResource(R.string.av_hermes_manual_model)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = url.startsWith("http://") || url.startsWith("https://"),
                onClick = {
                    val cfg = AgentBackendConfig(
                        displayName = "Hermes Agent",
                        type = BackendType.HERMES_API_SERVER,
                        baseUrl = url.trim(),
                        apiKeyOrToken = key.trim().ifEmpty { null },
                        modelName = model.ifBlank { "hermes-agent" },
                        isPrimary = repo.backends.value.isEmpty(),
                    )
                    repo.upsert(cfg)
                    if (cfg.isPrimary) repo.setPrimary(cfg.id)
                    status = "✓ " + context.getString(R.string.av_hermes_manual_added)
                },
            ) { Text(stringResource(R.string.av_hermes_manual_add)) }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DonePane(backends: List<AgentBackendConfig>) {
    Text(stringResource(R.string.av_setup_done_title) + " 🎉", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    if (backends.isEmpty()) {
        Text(stringResource(R.string.av_setup_done_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        backends.forEach { b ->
            Text(
                "· ${b.displayName}${if (b.isPrimary) " (" + stringResource(R.string.primary_backend) + ")" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
}
