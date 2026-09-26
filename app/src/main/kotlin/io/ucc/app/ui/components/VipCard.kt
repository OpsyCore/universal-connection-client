package io.ucc.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.ucc.app.BuildConfig
import io.ucc.app.R

/**
 * Official-channel card (v1.0.3; file/keys keep the `vip` name). Rendered **only** when `BuildConfig.VIP_URL`
 * is non-blank (default: the official Telegram channel; override/hide via `UCC_VIP_URL` / `vipUrl`). Opens the URL in the system handler (Telegram for t.me / tg:// links). Theme colours, string
 * resources (en + fa), no tracking, no network call of its own.
 */
@Composable
fun VipCard(modifier: Modifier = Modifier, url: String = BuildConfig.VIP_URL) {
    if (url.isBlank()) return
    val context = LocalContext.current
    val cannotOpen = stringResource(R.string.vip_cannot_open)
    val open = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, cannotOpen, Toast.LENGTH_SHORT).show()
        }
    }
    Card(
        modifier = modifier.fillMaxWidth().glass(base = MaterialTheme.colorScheme.tertiaryContainer),
        onClick = open,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vip_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.vip_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = open) { Text(stringResource(R.string.vip_action)) }
        }
    }
}
