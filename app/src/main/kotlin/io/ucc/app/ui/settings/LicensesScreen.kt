package io.ucc.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.data.Notices
import io.ucc.core.engine.ThirdPartyNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open-source licenses. Summarises every distributed component (engine list
 * comes from the selected CoreFactory, application list from [Notices]) and
 * shows the full GPL‑3.0 text bundled in assets. Repository-level notices live
 * in THIRD_PARTY_NOTICES.md; this screen does not replace them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(notices: Notices, onBack: () -> Unit) {
    var showGpl by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.licenses_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text(stringResource(R.string.licenses_no_warranty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedButton(onClick = { showGpl = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.licenses_full_gpl)) } }

            item { SectionLabel(stringResource(R.string.licenses_engine)) }
            items(notices.engine, key = { "e-" + it.component }) { NoticeCard(it) }
            item { SectionLabel(stringResource(R.string.licenses_application)) }
            items(notices.application, key = { "a-" + it.component }) { NoticeCard(it) }
        }
    }
    if (showGpl) GplDialog(onClose = { showGpl = false })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NoticeCard(n: ThirdPartyNotice) {
    val uriHandler = LocalUriHandler.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(n.component, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            // Version / license / URL are technical identifiers: always LTR.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(n.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(n.license, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { runCatching { uriHandler.openUri(n.url) } }, contentPadding = PaddingValues(0.dp)) {
                        Text(n.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            n.additionalTerms?.let {
                Text(stringResource(R.string.licenses_additional_terms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GplDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) { runCatching { context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("GNU GPL v3") },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done_action_close)) } },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
    )
}
