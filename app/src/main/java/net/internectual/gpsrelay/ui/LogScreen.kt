package net.internectual.gpsrelay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.internectual.gpsrelay.GpsRelayApp
import net.internectual.gpsrelay.client.NavLauncher
import net.internectual.gpsrelay.log.Direction
import net.internectual.gpsrelay.log.NavLogEntry
import net.internectual.gpsrelay.protocol.Destination
import net.internectual.gpsrelay.server.NavDispatcher

/** Every link intercepted on the server or received on the client, newest first. */
@Composable
fun LogScreen(onShowOnMap: (Destination) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GpsRelayApp
    val entries by app.navLog.entries.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${entries.size} links", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Links show up here when the server catches a navigation request or the client receives one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    LogItem(
                        ctx, e,
                        isExpanded = expanded == e.id,
                        onToggle = { expanded = if (expanded == e.id) null else e.id },
                        onShowOnMap = onShowOnMap,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the log?") },
            confirmButton = {
                TextButton(onClick = {
                    app.navLog.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogItem(
    ctx: Context,
    e: NavLogEntry,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onShowOnMap: (Destination) -> Unit,
) {
    val app = ctx.applicationContext as GpsRelayApp
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (e.direction == Direction.OUT) "Sent to client" else "Received from server") +
                        " · " + DateUtils.getRelativeDateTimeString(ctx, e.time, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                StatusChip(e.status)
            }
            e.dest?.let { Text(it.describe(), style = MaterialTheme.typography.bodyMedium) }
            Text(
                e.raw,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = if (isExpanded) 10 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isExpanded) {
                e.sourceApp?.let { Text("From: $it", style = MaterialTheme.typography.bodySmall) }
                e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val dest = e.dest
                    if (dest?.hasCoords == true) TextButton(onClick = { onShowOnMap(dest) }) { Text("Map") }
                    if (dest != null && e.direction == Direction.OUT) {
                        TextButton(onClick = { NavDispatcher.resend(ctx, dest, e.raw, e.sourceApp) }) { Text("Re-send") }
                    }
                    if (dest != null && e.direction == Direction.IN) {
                        TextButton(onClick = {
                            NavLauncher.launch(ctx, dest, app.prefs.current.navAppPackage, fromForeground = true)
                        }) { Text("Open again") }
                    }
                    TextButton(onClick = { copy(ctx, e.raw) }) { Text("Copy") }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        "failed" -> MaterialTheme.colorScheme.error
        "acked", "launched", "sent" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(onClick = {}, label = { Text(status, color = color, style = MaterialTheme.typography.labelSmall) })
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
