package dev.starlinkguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.starlinkguard.core.alert.EventType
import dev.starlinkguard.core.alert.TheftEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventLogScreen(
    events: List<TheftEvent>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (events.isEmpty()) {
            Text(
                "Nothing has happened yet. Arming, alarms and dropped connections all show up here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        TextButton(onClick = onClear, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Clear history")
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Newest first — the reason you opened this screen is almost always the last thing.
            items(events.reversed(), key = { it.id }) { event ->
                EventRow(event)
            }
        }
    }
}

@Composable
private fun EventRow(event: TheftEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = event.type.label(),
                style = MaterialTheme.typography.titleSmall,
                color = event.type.tint(),
            )
            Text(event.message, style = MaterialTheme.typography.bodyMedium)
            event.triggers.forEach {
                Text("• ${it.describe()}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                dateFormat.format(Date(event.timestampMs)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun EventType.label(): String = when (this) {
    EventType.ARMED -> "Armed"
    EventType.BASELINE_CAPTURED -> "Reference recorded"
    EventType.DISARMED -> "Disarmed"
    EventType.ALARM -> "ALARM"
    EventType.ALARM_STOPPED -> "Alarm stopped"
    EventType.DISH_UNREACHABLE -> "Dish unreachable"
    EventType.DISH_RECOVERED -> "Dish back"
    EventType.WEBHOOK_FAILED -> "Webhook failed"
}

private fun EventType.tint(): Color = when (this) {
    EventType.ALARM -> Color(0xFFC62828)
    EventType.WEBHOOK_FAILED, EventType.DISH_UNREACHABLE -> Color(0xFFF9A825)
    else -> Color(0xFF2E7D32)
}

private val dateFormat = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
