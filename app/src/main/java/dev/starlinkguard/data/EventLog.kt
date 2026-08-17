package dev.starlinkguard.data

import android.content.Context
import android.util.Log
import dev.starlinkguard.core.alert.EventType
import dev.starlinkguard.core.alert.TheftEvent
import dev.starlinkguard.core.detect.Trigger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An append-only history of what the monitor has done, kept as JSON lines on disk.
 *
 * A file rather than a database: the log is a few hundred short records that are only ever
 * appended and read back whole, so a database would add a code generator to the build for no
 * benefit.
 */
class EventLog(context: Context) {

    private val file = File(context.filesDir, "events.jsonl")
    private val mutex = Mutex()

    private val _events = MutableStateFlow<List<TheftEvent>>(emptyList())
    val events: StateFlow<List<TheftEvent>> = _events.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _events.value = readFromDisk()
        }
    }

    suspend fun append(
        type: EventType,
        message: String,
        triggers: List<Trigger> = emptyList(),
        dishSerial: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): TheftEvent {
        val event = TheftEvent(
            timestampMs = timestampMs,
            type = type,
            message = message,
            triggers = triggers,
            dishSerial = dishSerial,
        )
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = (_events.value + event).takeLast(MAX_ENTRIES)
                _events.value = updated
                runCatching {
                    file.writeText(updated.joinToString("\n") { encode(it) })
                }.onFailure { Log.w(TAG, "could not write the event log", it) }
            }
        }
        return event
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _events.value = emptyList()
            runCatching { file.delete() }
        }
        Unit
    }

    private fun readFromDisk(): List<TheftEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                // One corrupt line must not cost the user the whole history.
                .mapNotNull { line -> runCatching { decode(line) }.getOrNull() }
                .takeLast(MAX_ENTRIES)
        }.onFailure { Log.w(TAG, "could not read the event log", it) }.getOrDefault(emptyList())
    }

    private fun encode(event: TheftEvent) = TheftEvent.json.encodeToString(TheftEvent.serializer(), event)

    private fun decode(line: String) = TheftEvent.json.decodeFromString(TheftEvent.serializer(), line)

    private companion object {
        const val MAX_ENTRIES = 300
        const val TAG = "EventLog"
    }
}
